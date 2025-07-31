package xyz.theforks.ckvshader.patterns;

import xyz.theforks.ckvshader.util.GLUtil;
import xyz.theforks.ckvshader.util.ShaderCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.GLBuffers;
import heronarts.glx.GLX;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.audio.GraphicMeter;
import heronarts.lx.color.LXColor;
import heronarts.lx.command.LXCommand;
import heronarts.lx.model.LXPoint;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.MutableParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;
import heronarts.lx.utils.LXUtils;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UISlider;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Logger;

import static com.jogamp.opengl.GL.GL_ARRAY_BUFFER;
import static com.jogamp.opengl.GL.GL_FLOAT;
import static com.jogamp.opengl.GL.GL_POINTS;
import static com.jogamp.opengl.GL.GL_STATIC_DRAW;
import static com.jogamp.opengl.GL2ES2.GL_VERTEX_SHADER;
import static com.jogamp.opengl.GL2ES3.*;

/**
 * Specialized Navier-Stokes fluid simulation pattern with multi-texture state management.
 * Supports velocity field, pressure field, and density field as separate textures for
 * accurate fluid dynamics computation.
 */
@LXCategory(LXCategory.FORM)
public class CkVFluidShader extends LXPattern implements UIDeviceControls<CkVFluidShader> {
    private static final Logger logger = Logger.getLogger(CkVFluidShader.class.getName());
    public GL3 gl;

    StringParameter scriptName = new StringParameter("scriptName", "navierStokes");
    CompoundParameter speed = new CompoundParameter("speed", 1f, 0f, 20f);
    CompoundParameter alphaThresh = new CompoundParameter("alfTh", 0.1f, -0.1f, 1f)
            .setDescription("Intensity values below threshold will use transparency.");

    // Core fluid simulation parameters (others will be loaded from ISF metadata)

    // Shader parameters loaded from ISF
    LinkedHashMap<String, CompoundParameter> scriptParams = new LinkedHashMap<String, CompoundParameter>();
    Map<String, Integer> paramLocations = new HashMap<String, Integer>();
    public final MutableParameter onReload = new MutableParameter("Reload");
    public final StringParameter error = new StringParameter("Error", null);
    private UIButton openButton;

    // Shader caching
    private ShaderCache shaderCache;
    private boolean forceReload = false;

    // Multi-texture fluid state management
    public static final int FLUID_TEXTURE_SIZE = 256; // Fluid simulation resolution
    private int[] velocityTextureHandles = {0, 0}; // Ping-pong velocity textures
    private int[] pressureTextureHandles = {0, 0}; // Ping-pong pressure textures
    private int[] densityTextureHandles = {0, 0};  // Ping-pong density textures
    private int[] frameBufferHandles = {0, 0};     // Frame buffers for off-screen rendering
    private int currentTextureIndex = 0;           // Current texture index for ping-pong

    // Uniform locations for fluid textures
    private int velocityTextureLoc = -1;
    private int pressureTextureLoc = -1;
    private int densityTextureLoc = -1;
    private int audioTextureLoc = -1;

    // Audio texture for reactive effects
    int[] audioTextureHandle = {0};
    byte[] fft = new byte[1024];

    // GL state management
    private interface Buffer {
        int VERTEX = 0;
        int TBO = 1;
        int MAX = 2;
    }

    protected FloatBuffer tfbBuffer;
    protected FloatBuffer vertexBuffer;
    protected IntBuffer bufferNames = GLBuffers.newDirectIntBuffer(Buffer.MAX);
    protected int shaderProgramId = -1;
    protected int fTimeLoc = -2;
    protected double totalTime = 0.0;
    protected JsonObject isfObj;
    float[] ledPositions;

    // Texture resource management
    private GLUtil.TextureLimits textureLimits;

    public CkVFluidShader(LX lx) {
        super(lx);

        addParameter("scriptName", scriptName);
        addParameter("speed", speed);
        addParameter("alfTh", alphaThresh);
        // Other parameters will be loaded from ISF shader metadata

        CkVShader.initializeGLContext(lx);
        xyz.theforks.ckvshader.util.ShaderResourceUtil.exportDefaultShaders(lx);
        
        shaderCache = ShaderCache.getInstance(lx);
        
        glInit();
    }

    protected void updateLedPositions() {
        for (int i = 0; i < model.points.length; i++) {
            ledPositions[i * 3] = model.points[i].xn;
            ledPositions[i * 3 + 1] = model.points[i].yn;
            ledPositions[i * 3 + 2] = model.points[i].zn;
        }
    }

    protected void updateAudioTexture() {
        if (audioTextureLoc < 0 || audioTextureHandle[0] == 0) {
            return;
        }
        
        GraphicMeter eq = lx.engine.audio.meter;
        for (int i = 0; i < 1024; i++) {
            int bandVal = (int)(eq.getBandf(i%16) * 255.0);
            fft[i] = (byte)(bandVal);
        }
        ByteBuffer fftBuf = ByteBuffer.wrap(fft);
        
        gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
        gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 512, 2, 0, GL_RED, GL_UNSIGNED_BYTE, fftBuf);
        GLUtil.checkGLError(gl, "audio texture update");
    }

    private void initFluidTextures() {
        // Generate texture handles for fluid state
        gl.glGenTextures(2, velocityTextureHandles, 0);
        gl.glGenTextures(2, pressureTextureHandles, 0);
        gl.glGenTextures(2, densityTextureHandles, 0);
        gl.glGenFramebuffers(2, frameBufferHandles, 0);

        GLUtil.checkGLError(gl, "fluid texture generation");

        // Initialize velocity textures
        for (int i = 0; i < 2; i++) {
            gl.glBindTexture(GL_TEXTURE_2D, velocityTextureHandles[i]);
            gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, 
                           0, GL_RG, GL_FLOAT, null);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        // Initialize pressure textures
        for (int i = 0; i < 2; i++) {
            gl.glBindTexture(GL_TEXTURE_2D, pressureTextureHandles[i]);
            gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, 
                           0, GL_RED, GL_FLOAT, null);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        // Initialize density textures
        for (int i = 0; i < 2; i++) {
            gl.glBindTexture(GL_TEXTURE_2D, densityTextureHandles[i]);
            gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, FLUID_TEXTURE_SIZE, FLUID_TEXTURE_SIZE, 
                           0, GL_RED, GL_FLOAT, null);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        GLUtil.checkGLError(gl, "fluid texture initialization");
        LX.log("Initialized fluid textures with size: " + FLUID_TEXTURE_SIZE + "x" + FLUID_TEXTURE_SIZE);
    }

    public void glInit() {
        LXPoint[] points = model.points;
        ledPositions = new float[points.length * 3];
        updateLedPositions();

        CkVShader.glDrawable.getContext().makeCurrent();
        gl = CkVShader.glDrawable.getGL().getGL3();
        
        // Query texture limits from hardware
        if (textureLimits == null) {
            textureLimits = GLUtil.queryTextureLimits(gl);
            LX.log("OpenGL Texture Limits: " + textureLimits.toString());
        }
        
        vertexBuffer = FloatBuffer.wrap(ledPositions);
        tfbBuffer = GLBuffers.newDirectFloatBuffer(vertexBuffer.capacity());
        
        gl.glGenBuffers(Buffer.MAX, bufferNames);
        GLUtil.checkGLError(gl, "buffer generation");
        
        // Initialize fluid simulation textures
        initFluidTextures();
        
        // Set up audio texture
        gl.glGenTextures(1, audioTextureHandle, 0);
        if (audioTextureHandle[0] > 0) {
            gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_MIRRORED_REPEAT);
            gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_MIRRORED_REPEAT);
            LX.log("Created audio texture with handle: " + audioTextureHandle[0]);
        }

        CkVShader.glDrawable.getContext().release();
        reloadShader(scriptName.getString());
    }

    private List<String> newSliderKeys = new ArrayList<String>();
    private List<String> removeSliderKeys = new ArrayList<String>();

    public void reloadShader(String shaderName) {
        reloadShader(shaderName, true);
    }

    public void reloadShader(String shaderName, boolean clearSliders) {
        CkVShader.glDrawable.getContext().makeCurrent();
        if (shaderProgramId != -1)
            gl.glDeleteProgram(shaderProgramId);

        if (clearSliders) clearSliders();
        newSliderKeys.clear();
        removeSliderKeys.clear();

        String shaderDir = GLUtil.shaderDir(lx);
        boolean useCache = !forceReload && shaderCache.isCacheValid(shaderName, shaderDir) && 
                          shaderCache.isGLContextValid(gl);

        if (useCache) {
            // Try to load from cache (similar to parent class implementation)
            ShaderCache.CachedShaderResult cachedResult = shaderCache.loadCachedShader(shaderName, gl);
            if (cachedResult != null) {
                LX.log("Loading fluid shader from cache: " + shaderName);
                shaderProgramId = cachedResult.programId;
                isfObj = cachedResult.entry.isfMetadata;
                paramLocations.clear();
                paramLocations.putAll(cachedResult.entry.uniformLocations);
                
                // Restore parameters and find texture uniform locations
                restoreParametersFromCache();
                findTextureUniformLocations();
                
                CkVShader.glDrawable.getContext().release();
                onReload.bang();
                forceReload = false;
                return;
            }
        }

        // Compile from source
        LX.log("Compiling fluid shader from source: " + shaderName);
        compileShaderFromSource(shaderName);
        
        CkVShader.glDrawable.getContext().release();
        onReload.bang();
        forceReload = false;
    }

    private void restoreParametersFromCache() {
        if (isfObj != null && isfObj.has("INPUTS")) {
            JsonArray inputs = isfObj.getAsJsonArray("INPUTS");
            for (int k = 0; k < inputs.size(); k++) {
                JsonObject input = (JsonObject)inputs.get(k);
                String pName = input.get("NAME").getAsString();
                float pDefault = input.get("DEFAULT").getAsFloat();
                float pMin = input.get("MIN").getAsFloat();
                float pMax = input.get("MAX").getAsFloat();
                
                if (!scriptParams.containsKey(pName)) {
                    CompoundParameter cp = new CompoundParameter(pName, pDefault, pMin, pMax);
                    scriptParams.put(pName, cp);
                    addParameter(pName, cp);
                }
                newSliderKeys.add(pName);
            }
        }
    }

    private void compileShaderFromSource(String shaderName) {
        shaderProgramId = gl.glCreateProgram();
        String shaderSource = "";
        Set<String> dependencies = new HashSet<>();

        try {
            GLUtil.ShaderLoadResult result = GLUtil.loadShaderWithDependencies(GLUtil.shaderDir(lx), shaderName + ".vtx");
            shaderSource = result.source;
            dependencies = result.dependencies;
        } catch (Exception ex) {
            LX.log("Error loading fluid shader: " + ex.getMessage());
            return;
        }

        // Parse ISF metadata
        parseISFMetadata(shaderSource);
        
        // Create and link shader
        try {
            GLUtil.createShader(gl, shaderProgramId, shaderSource, GL_VERTEX_SHADER);
            gl.glTransformFeedbackVaryings(shaderProgramId, 1, new String[]{"outColor"}, GL_INTERLEAVED_ATTRIBS);
            GLUtil.link(gl, shaderProgramId);
        } catch (Exception ex) {
            LX.log("Error compiling fluid shader: " + ex.getMessage());
            // Clean up failed shader program
            if (shaderProgramId > 0) {
                gl.glDeleteProgram(shaderProgramId);
                shaderProgramId = -1;
            }
            return;
        }

        // Find uniform locations
        findUniformLocations();
        
        // Cache the compiled shader
        try {
            shaderCache.cacheShader(shaderName, GLUtil.shaderDir(lx), shaderProgramId, 
                                  paramLocations, isfObj, dependencies, gl);
        } catch (Exception ex) {
            LX.log("Warning: Failed to cache fluid shader: " + ex.getMessage());
        }
    }

    private void parseISFMetadata(String shaderSource) {
        int endOfComment = shaderSource.indexOf("*/");
        int startOfComment = shaderSource.indexOf("/*");
        if (startOfComment >= 0 && endOfComment > startOfComment) {
            String jsonDef = shaderSource.substring(startOfComment + 2, endOfComment);
            isfObj = (JsonObject)new JsonParser().parse(jsonDef);
            
            if (isfObj.has("INPUTS")) {
                JsonArray inputs = isfObj.getAsJsonArray("INPUTS");
                for (int k = 0; k < inputs.size(); k++) {
                    JsonObject input = (JsonObject)inputs.get(k);
                    String pName = input.get("NAME").getAsString();
                    float pDefault = input.get("DEFAULT").getAsFloat();
                    float pMin = input.get("MIN").getAsFloat();
                    float pMax = input.get("MAX").getAsFloat();
                    
                    if (!scriptParams.containsKey(pName)) {
                        CompoundParameter cp = new CompoundParameter(pName, pDefault, pMin, pMax);
                        scriptParams.put(pName, cp);
                        addParameter(pName, cp);
                    }
                    newSliderKeys.add(pName);
                }
            }
        }
    }

    private void findUniformLocations() {
        paramLocations.clear();
        fTimeLoc = gl.glGetUniformLocation(shaderProgramId, "fTime");
        
        for (String scriptParam : scriptParams.keySet()) {
            int paramLoc = gl.glGetUniformLocation(shaderProgramId, scriptParam);
            paramLocations.put(scriptParam, paramLoc);
        }
        
        findTextureUniformLocations();
    }

    private void findTextureUniformLocations() {
        velocityTextureLoc = gl.glGetUniformLocation(shaderProgramId, "velocityTexture");
        pressureTextureLoc = gl.glGetUniformLocation(shaderProgramId, "pressureTexture");
        densityTextureLoc = gl.glGetUniformLocation(shaderProgramId, "densityTexture");
        audioTextureLoc = gl.glGetUniformLocation(shaderProgramId, "audioTexture");
        
        LX.log("Fluid texture uniform locations - Velocity: " + velocityTextureLoc + 
               ", Pressure: " + pressureTextureLoc + ", Density: " + densityTextureLoc);
    }

    public void glRun(double deltaMs) {
        // Skip if shader failed to compile
        if (shaderProgramId <= 0) {
            return;
        }
        
        totalTime += deltaMs / 1000.0;
        CkVShader.glDrawable.getContext().makeCurrent();
        
        updateAudioTexture();
        updateLedPositions();
        
        // Bind vertex data
        gl.glBindBuffer(GL_ARRAY_BUFFER, bufferNames.get(Buffer.VERTEX));
        gl.glBufferData(GL_ARRAY_BUFFER, vertexBuffer.capacity() * Float.BYTES, vertexBuffer, GL_STATIC_DRAW);
        int inputAttrib = gl.glGetAttribLocation(shaderProgramId, "position");
        gl.glEnableVertexAttribArray(inputAttrib);
        gl.glVertexAttribPointer(inputAttrib, 3, GL_FLOAT, false, 0, 0);

        // Bind transform feedback buffer
        gl.glBindBuffer(GL_ARRAY_BUFFER, bufferNames.get(Buffer.TBO));
        gl.glBufferData(GL_ARRAY_BUFFER, tfbBuffer.capacity() * Float.BYTES, tfbBuffer, GL_STATIC_READ);
        gl.glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, bufferNames.get(Buffer.TBO));

        // Set up shader and uniforms
        gl.glEnable(GL_RASTERIZER_DISCARD);
        gl.glUseProgram(shaderProgramId);

        // Set time and parameters (with null checks)
        if (fTimeLoc >= 0) {
            gl.glUniform1f(fTimeLoc, speed.getValuef() * (float)totalTime);
        }
        for (String paramName : scriptParams.keySet()) {
            Integer location = paramLocations.get(paramName);
            if (location != null && location >= 0) {
                gl.glUniform1f(location, scriptParams.get(paramName).getValuef());
            }
        }

        // Bind fluid state textures
        bindFluidTextures();

        // Bind audio texture
        if (audioTextureHandle[0] > 0 && audioTextureLoc >= 0) {
            gl.glActiveTexture(GL_TEXTURE3);
            gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
            gl.glUniform1i(audioTextureLoc, 3);
        }

        // Execute shader
        gl.glBeginTransformFeedback(GL_POINTS);
        gl.glDrawArrays(GL_POINTS, 0, model.points.length);
        gl.glEndTransformFeedback();
        gl.glFlush();

        // Read back results
        gl.glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0, tfbBuffer.capacity() * Float.BYTES, tfbBuffer);

        gl.glUseProgram(0);
        gl.glDisable(GL_RASTERIZER_DISCARD);

        // Swap ping-pong textures for next frame
        currentTextureIndex = 1 - currentTextureIndex;

        CkVShader.glDrawable.getContext().release();
    }

    private void bindFluidTextures() {
        // Bind fluid state textures to appropriate texture units
        if (velocityTextureLoc >= 0) {
            gl.glActiveTexture(GL_TEXTURE0);
            gl.glBindTexture(GL_TEXTURE_2D, velocityTextureHandles[currentTextureIndex]);
            gl.glUniform1i(velocityTextureLoc, 0);
        }
        
        if (pressureTextureLoc >= 0) {
            gl.glActiveTexture(GL_TEXTURE1);
            gl.glBindTexture(GL_TEXTURE_2D, pressureTextureHandles[currentTextureIndex]);
            gl.glUniform1i(pressureTextureLoc, 1);
        }
        
        if (densityTextureLoc >= 0) {
            gl.glActiveTexture(GL_TEXTURE2);
            gl.glBindTexture(GL_TEXTURE_2D, densityTextureHandles[currentTextureIndex]);
            gl.glUniform1i(densityTextureLoc, 2);
        }
    }

    @Override
    public void onParameterChanged(LXParameter p) {
        if (p == this.scriptName) {
            LX.log("Fluid shader name parameter changed!");
            reloadShader(((StringParameter)p).getString());
        }
    }

    @Override
    public void onActive() {
        super.onActive();
        totalTime = 0f;
    }

    private void clearSliders() {
        for (String key : scriptParams.keySet()) {
            removeParameter(key);
        }
        scriptParams.clear();
    }

    @Override
    public void dispose() {
        if (gl != null) {
            CkVShader.glDrawable.getContext().makeCurrent();
            
            // Clean up fluid textures
            if (velocityTextureHandles[0] > 0) {
                gl.glDeleteTextures(2, velocityTextureHandles, 0);
            }
            if (pressureTextureHandles[0] > 0) {
                gl.glDeleteTextures(2, pressureTextureHandles, 0);
            }
            if (densityTextureHandles[0] > 0) {
                gl.glDeleteTextures(2, densityTextureHandles, 0);
            }
            if (frameBufferHandles[0] > 0) {
                gl.glDeleteFramebuffers(2, frameBufferHandles, 0);
            }
            
            // Clean up audio texture
            if (audioTextureHandle[0] > 0) {
                gl.glDeleteTextures(1, audioTextureHandle, 0);
            }
            
            // Clean up shader
            if (shaderProgramId != -1) {
                gl.glDeleteProgram(shaderProgramId);
            }
            
            GLUtil.checkGLError(gl, "fluid resource disposal");
            CkVShader.glDrawable.getContext().release();
        }
        
        super.dispose();
    }

    public void run(double deltaMs) {
        glRun(deltaMs);
        LXPoint[] points = model.points;
        float threshold = alphaThresh.getValuef();
        
        for (int i = 0; i < points.length; i++) {
            float red = tfbBuffer.get(i * 3);
            float green = tfbBuffer.get(i * 3 + 1);
            float blue = tfbBuffer.get(i * 3 + 2);
            int color = LXColor.rgbf(red, green, blue);
            float bright = LXColor.luminosity(color) / 100f;
            
            if (bright < threshold) {
                float alpha = (bright / threshold);
                colors[points[i].index] = LXColor.rgba(LXColor.red(color),
                    LXColor.green(color),
                    LXColor.blue(color),
                    (int)(255f * alpha));
            } else {
                colors[points[i].index] = color;
            }
        }
    }

    @Override
    public void buildDeviceControls(LXStudio.UI ui, UIDevice uiDevice, CkVFluidShader pattern) {
        int minContentWidth = 300;
        uiDevice.setContentWidth(minContentWidth);

        final UILabel fileLabel = (UILabel)
            new UILabel(0, 0, 120, 18)
                .setLabel(pattern.scriptName.getString())
                .setBackgroundColor(LXColor.BLACK)
                .setBorderRounding(4)
                .setTextAlignment(VGraphics.Align.CENTER, VGraphics.Align.MIDDLE)
                .setTextOffset(0, -1)
                .addToContainer(uiDevice);

        pattern.scriptName.addListener(p -> {
            fileLabel.setLabel(pattern.scriptName.getString());
        });

        final UIButton resetButton = (UIButton) new UIButton(148, 0, 18, 18) {
            @Override
            public void onToggle(boolean on) {
                if (on) {
                    lx.engine.addTask(() -> {
                        logger.info("Force reloading fluid shader (bypassing cache)");
                        forceReload = true;
                        reloadShader(scriptName.getString(), false);
                    });
                }
            }
        }.setIcon(ui.theme.iconLoad)
          .setMomentary(true)
          .setDescription("Force reload fluid shader")
          .addToContainer(uiDevice);

        final UI2dContainer sliders = (UI2dContainer)
            UI2dContainer.newHorizontalContainer(uiDevice.getContentHeight() - 20, 2)
                .setPosition(0, 20)
                .addToContainer(uiDevice);

        // Add sliders for core and script parameters
        pattern.onReload.addListener(p -> {
            sliders.removeAllChildren();
            
            // Core framework parameters
            new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, speed)
                .addToContainer(sliders);
            new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, alphaThresh)
                .addToContainer(sliders);

            // All fluid simulation parameters loaded from shader ISF metadata
            for (CompoundParameter scriptParam : pattern.scriptParams.values()) {
                new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, scriptParam)
                    .addToContainer(sliders);
            }
            
            float contentWidth = LXUtils.maxf(minContentWidth, sliders.getContentWidth());
            uiDevice.setContentWidth(contentWidth);
        }, true);
    }
}