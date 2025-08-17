package xyz.theforks.ckvshader.patterns;

import xyz.theforks.ckvshader.util.GLUtil;
import xyz.theforks.ckvshader.util.ShaderCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.GLBuffers;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
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

@LXCategory(LXCategory.FORM)
public class CkVShaderFrames extends LXPattern implements UIDeviceControls<CkVShaderFrames> {
  private static final Logger logger = Logger.getLogger(CkVShaderFrames.class.getName());
  public GL3 gl;

  StringParameter scriptName = new StringParameter("scriptName", "CkVShader/shaders/texture.vtx");
  StringParameter frameDir = new StringParameter("frameDir", "");
  CompoundParameter frameNumber = new CompoundParameter("frame", 0, 0, 1);
  CompoundParameter speed = new CompoundParameter("speed", 1f, 0f, 20f);
  CompoundParameter alphaThresh = new CompoundParameter("alfTh", 0.1f, -0.1f, 1f).
    setDescription("Intensity values below threshold will use transparency.");

  // These parameters are loaded from the ISF Json declaration at the top of the shader
  LinkedHashMap<String, CompoundParameter> scriptParams = new LinkedHashMap<String, CompoundParameter>();
  // For each script based parameter, store the uniform location in the compiled shader.  We use this
  // to pass in the values for each frame.
  Map<String, Integer> paramLocations = new HashMap<String, Integer>();
  public final MutableParameter onReload = new MutableParameter("Reload");
  public final StringParameter error = new StringParameter("Error", null);
  private UIButton openButton;
  private UIButton frameDirButton;
  
  // Frame sequence management
  private List<com.jogamp.opengl.util.texture.Texture> frameTextures = new ArrayList<>();
  private List<String> frameFiles = new ArrayList<>();
  private String currentFrameDir = "";
  private int currentFrameIndex = 0;
  
  public int textureLoc = -3;
  public int fftTextureLoc = -3;

  public final int TEXTURE_SIZE = 512;

  public CkVShaderFrames(LX lx) {
    super(lx);

    addParameter("scriptName", scriptName);
    addParameter("frameDir", frameDir);
    addParameter("frame", frameNumber);
    addParameter("speed", speed);
    addParameter("alfTh", alphaThresh);

    CkVShader.initializeGLContext(lx);
    // Export default shaders from JAR resources to filesystem
    xyz.theforks.ckvshader.util.ShaderResourceUtil.exportDefaultShaders(lx);
    
    shaderCache = ShaderCache.getInstance(lx);
    
    glInit();
  }

  private interface Buffer {
    int VERTEX = 0;
    int TBO = 1;
    int MAX = 2;
  }

  // Destination for transform feedback buffer when copied back from the GPU
  protected FloatBuffer tfbBuffer;
  // Staging buffer for vertex data to be copied to the VBO on the GPU
  protected FloatBuffer vertexBuffer;
  // Stores the buffer IDs for the buffer IDs allocated on the GPU.
  protected IntBuffer bufferNames = GLBuffers.newDirectIntBuffer(Buffer.MAX);
  // The shader's ID on the GPU.
  protected int shaderProgramId = -1;

  protected int fTimeLoc = -2;

  protected double totalTime = 0.0;

  protected JsonObject isfObj;

  float[] ledPositions;

  int[] audioTextureHandle = {0};
  byte[] fft = new byte[1024];
  
  // Shader caching
  private ShaderCache shaderCache;
  private boolean forceReload = false;
  
  // Texture resource management
  private GLUtil.TextureLimits textureLimits;
  private boolean textureInitialized = false;
  
  protected void updateLedPositions() {
    for (int i = 0; i < model.points.length; i++) {
      ledPositions[i * 3] = model.points[i].xn;
      ledPositions[i * 3 + 1] = model.points[i].yn;
      ledPositions[i * 3 + 2] = model.points[i].zn;
    }
  }

  protected void updateAudioTexture() {
    // Create Audio FFT texture
    if (fftTextureLoc < 0 || audioTextureHandle[0] == 0) {
      return;
    }
    
    // Validate texture handle
    if (audioTextureHandle[0] <= 0) {
      LX.log("Invalid audio texture handle: " + audioTextureHandle[0]);
      return;
    }
    
    GraphicMeter eq = lx.engine.audio.meter;
    for (int i = 0; i < 1024; i++) {
      int bandVal = (int)(eq.getBandf(i%16) * 255.0);
      fft[i++] = (byte)(bandVal);
    }
    ByteBuffer fftBuf = ByteBuffer.wrap(fft);
    
    gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
    GLUtil.checkGLError(gl, "audio texture bind");
    
    gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 512, 2, 0, GL_RED, GL_UNSIGNED_BYTE, fftBuf);
    GLUtil.checkGLError(gl, "audio texture data update");
  }

  /**
   * Load all frames from the specified directory into memory
   */
  private void loadFrameSequence(String directoryPath) {
    LX.log("Loading frame sequence from: " + directoryPath);
    
    // Clear existing frames
    clearFrameTextures();
    frameFiles.clear();
    currentFrameDir = directoryPath;
    
    if (directoryPath == null || directoryPath.isEmpty()) {
      frameNumber = new CompoundParameter("frame", 0, 0, 1);
      return;
    }
    
    File dir = new File(directoryPath);
    if (!dir.exists() || !dir.isDirectory()) {
      LX.log("Directory does not exist: " + directoryPath);
      frameNumber = new CompoundParameter("frame", 0, 0, 1);
      return;
    }
    
    // Get all image files and sort them
    File[] files = dir.listFiles((d, name) -> 
      name.toLowerCase().endsWith(".png") || 
      name.toLowerCase().endsWith(".jpg") || 
      name.toLowerCase().endsWith(".jpeg"));
    
    if (files == null || files.length == 0) {
      LX.log("No image files found in directory: " + directoryPath);
      frameNumber = new CompoundParameter("frame", 0, 0, 1);
      return;
    }
    
    Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
    
    CkVShader.glDrawable.getContext().makeCurrent();
    
    // Load all frames into memory
    for (File file : files) {
      try {
        BufferedImage image = ImageIO.read(file);
        if (image != null) {
          // Validate and resize texture if needed
          if (textureLimits != null) {
            if (!GLUtil.validateTextureSize(image.getWidth(), image.getHeight(), textureLimits)) {
              image = GLUtil.resizeTextureIfNeeded(image, textureLimits.maxTextureSize);
            }
          }
          
          com.jogamp.opengl.util.texture.Texture texture = 
            AWTTextureIO.newTexture(CkVShader.glDrawable.getGLProfile(), image, false);
          GLUtil.checkGLError(gl, "frame texture creation");
          
          if (texture != null) {
            // Set texture parameters
            texture.bind(gl);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_REPEAT);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_REPEAT);
            GLUtil.checkGLError(gl, "frame texture parameter setting");
            
            frameTextures.add(texture);
            frameFiles.add(file.getName());
            
            // Record texture creation for monitoring
            int bytesPerPixel = image.getColorModel().hasAlpha() ? 4 : 3;
            GLUtil.TextureMonitor.recordTextureCreation(image.getWidth(), image.getHeight(), bytesPerPixel);
          }
        }
      } catch (IOException e) {
        LX.log("Error loading frame: " + file.getName() + " - " + e.getMessage());
      }
    }
    
    CkVShader.glDrawable.getContext().release();
    
    // Update frame parameter range  
    if (frameTextures.size() > 0) {
      removeParameter("frame");
      frameNumber = new CompoundParameter("frame", 0, 0, frameTextures.size() - 1);
      addParameter("frame", frameNumber);
      LX.log("Loaded " + frameTextures.size() + " frames");
    } else {
      removeParameter("frame");
      frameNumber = new CompoundParameter("frame", 0, 0, 1);
      addParameter("frame", frameNumber);
      LX.log("No frames loaded from directory: " + directoryPath);
    }
  }
  
  private void clearFrameTextures() {
    if (gl != null && !frameTextures.isEmpty()) {
      CkVShader.glDrawable.getContext().makeCurrent();
      for (com.jogamp.opengl.util.texture.Texture texture : frameTextures) {
        if (texture != null) {
          texture.destroy(gl);
        }
      }
      CkVShader.glDrawable.getContext().release();
    }
    frameTextures.clear();
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

    // This is just a destination, make it large enough to accept all the vertex data.  The vertex
    // shader always outputs the all the elements.  To return just some of the points, attach a
    // geometry shader and filter there.  You will also need to carry along the lxpoint index with
    // the vertex data in that scenario to match it up after the transform feedback.
    tfbBuffer = GLBuffers.newDirectFloatBuffer(vertexBuffer.capacity());
    
    gl.glGenBuffers(Buffer.MAX, bufferNames);
    GLUtil.checkGLError(gl, "buffer generation");
    
    // Set up audio texture.
    gl.glGenTextures(1, audioTextureHandle, 0);
    GLUtil.checkGLError(gl, "audio texture generation");
    
    if (audioTextureHandle[0] > 0) {
      gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_MIRRORED_REPEAT);
      gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_MIRRORED_REPEAT);
      GLUtil.checkGLError(gl, "audio texture parameter setting");
      LX.log("Created audio texture with handle: " + audioTextureHandle[0]);
    } else {
      LX.log("Failed to generate audio texture handle");
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

    if (!GLUtil.CACHING_ENABLED) {
      useCache = false;
    }
    if (useCache) {
      // Try to load from cache
      ShaderCache.CachedShaderResult cachedResult = shaderCache.loadCachedShader(shaderName, gl);
      if (cachedResult != null) {
        LX.log("Loading shader from cache: " + shaderName);
        
        // Restore from cached data
        shaderProgramId = cachedResult.programId;
        isfObj = cachedResult.entry.isfMetadata;
        paramLocations.clear();
        paramLocations.putAll(cachedResult.entry.uniformLocations);
        for (String key : cachedResult.entry.uniformLocations.keySet()) {
          LX.log("Restoring parameter: " + key + " at location: " + cachedResult.entry.uniformLocations.get(key));
        }
        
        // Restore parameters from cached ISF metadata
        if (isfObj != null && isfObj.has("INPUTS")) {
          JsonArray inputs = isfObj.getAsJsonArray("INPUTS");
          for (int k = 0; k < inputs.size(); k++) {
            JsonObject input = (JsonObject)inputs.get(k);
            String pName = input.get("NAME").getAsString();
            String pType = input.get("TYPE").getAsString();
            float pDefault = input.get("DEFAULT").getAsFloat();
            float pMin = input.get("MIN").getAsFloat();
            float pMax = input.get("MAX").getAsFloat();
            
            if (clearSliders || (!clearSliders && !scriptParams.containsKey(pName))) {
              LX.log("Restoring parameter: " + pName + " with default: " + pDefault);
              CompoundParameter cp = new CompoundParameter(pName, pDefault, pMin, pMax);
              scriptParams.put(pName, cp);
              addParameter(pName, cp);
            }
            newSliderKeys.add(pName);
          }
        }
        
        // Clean up old parameters if needed
        if (!clearSliders) {
          for (String key : scriptParams.keySet()) {
            if (!newSliderKeys.contains(key)) {
              removeSliderKeys.add(key);
            }
          }
          for (String key : removeSliderKeys) {
            removeParameter(key);
            scriptParams.remove(key);
          }
        }
        
        // Find uniform locations from cached data
        fTimeLoc = gl.glGetUniformLocation(shaderProgramId, "fTime");
        LX.log("Found fTimeLoc at: " + fTimeLoc);
        textureLoc = gl.glGetUniformLocation(shaderProgramId, "textureSampler");
        LX.log("Found textureSampler at location: " + textureLoc);
        if (audioTextureHandle[0] != 0) {
          fftTextureLoc = gl.glGetUniformLocation(shaderProgramId, "audioTexture");
          LX.log("Found audioTexture at location: " + fftTextureLoc);
        }
        CkVShader.glDrawable.getContext().release();
        onReload.bang();
        forceReload = false; // Reset force reload flag
        return;
      }
    }

    // Cache miss or forced reload - compile from source
    LX.log("Compiling shader from source: " + shaderName);
    shaderProgramId = gl.glCreateProgram();
    String shaderSource = "";
    Set<String> dependencies = new HashSet<>();

    try {
      GLUtil.ShaderLoadResult result = GLUtil.loadShaderWithDependencies(shaderDir, shaderName + ".vtx");
      shaderSource = result.source;
      dependencies = result.dependencies;
    } catch (Exception ex) {
      LX.log("Error loading shader: " + ex.getMessage());
    }

    int endOfComment = shaderSource.indexOf("*/");
    int startOfComment = shaderSource.indexOf("/*");
    String jsonDef = shaderSource.substring(startOfComment + 2, endOfComment);
    isfObj = (JsonObject)new JsonParser().parse(jsonDef);
    JsonArray inputs = isfObj.getAsJsonArray("INPUTS");

    for (int k = 0; k < inputs.size(); k++) {
      JsonObject input = (JsonObject)inputs.get(k);
      String pName = input.get("NAME").getAsString();
      String pType = input.get("TYPE").getAsString(); // must be float for now
      float pDefault = input.get("DEFAULT").getAsFloat();
      float pMin = input.get("MIN").getAsFloat();
      float pMax =  input.get("MAX").getAsFloat();
      
      if (clearSliders || (!clearSliders && !scriptParams.containsKey(pName))) {
        CompoundParameter cp = new CompoundParameter(pName, pDefault, pMin, pMax);
        scriptParams.put(pName, cp);
        addParameter(pName, cp);
      }
      newSliderKeys.add(pName);
    }
    
    if (!clearSliders) {
      for (String key : scriptParams.keySet()) {
        if (!newSliderKeys.contains(key)) {
          removeSliderKeys.add(key);
        }
      }
      for (String key : removeSliderKeys) {
        removeParameter(key);
        scriptParams.remove(key);
      }
    }

    int shaderId = -1;
    try {
      shaderId = GLUtil.createShader(gl, shaderProgramId, shaderSource, GL_VERTEX_SHADER);
    } catch (Exception ex) {
      LX.log("Error creating shader: " + ex.getMessage());
    }

    gl.glTransformFeedbackVaryings(shaderProgramId, 1, new String[]{"outColor"}, GL_INTERLEAVED_ATTRIBS);
    GLUtil.link(gl, shaderProgramId);

    // Find uniform locations
    paramLocations.clear();
    fTimeLoc = gl.glGetUniformLocation(shaderProgramId, "fTime");
    LX.log("Found fTimeLoc at: " + fTimeLoc);
    for (String scriptParam : scriptParams.keySet()) {
      int paramLoc = gl.glGetUniformLocation(shaderProgramId, scriptParam);
      paramLocations.put(scriptParam, paramLoc);
    }

    for (String key : paramLocations.keySet()) {
      LX.log("Parameter: " + key + " at location: " + paramLocations.get(key));
    }

    textureLoc = gl.glGetUniformLocation(shaderProgramId, "textureSampler");
    LX.log("Found textureSampler at location: " + textureLoc);
    
    if (audioTextureHandle[0] != 0) {
      fftTextureLoc = gl.glGetUniformLocation(shaderProgramId, "audioTexture");
      LX.log("Found audioTexture at location: " + fftTextureLoc);
    }

    if (GLUtil.CACHING_ENABLED) {
    // Cache the compiled shader
      try {
        LX.log("Attempting to cache shader: " + shaderName + " with program ID: " + shaderProgramId);
        shaderCache.cacheShader(shaderName, shaderDir, shaderProgramId, paramLocations, isfObj, dependencies, gl);
        LX.log("Cache attempt completed for: " + shaderName);
      } catch (Exception ex) {
        LX.log("Warning: Failed to cache shader " + shaderName + ": " + ex.getMessage());
        ex.printStackTrace();
      }
    }

    CkVShader.glDrawable.getContext().release();
    onReload.bang();
    forceReload = false; // Reset force reload flag
  }

  @Override
  public void load(LX lx, JsonObject obj) {
    // Force-load the script name first so that slider parameter values can come after
    if (obj.has(LXComponent.KEY_PARAMETERS)) {
      JsonObject params = obj.getAsJsonObject(LXComponent.KEY_PARAMETERS);
      if (params.has("scriptName")) {
        this.scriptName.setValue(params.get("scriptName").getAsString());
      }
      if (params.has("frameDir")) {
        this.frameDir.setValue(params.get("frameDir").getAsString());
      }
    }
    super.load(lx, obj);
  }

  public void glRun(double deltaMs) {
    totalTime += deltaMs/1000.0;
    CkVShader.glDrawable.getContext().makeCurrent();
    updateAudioTexture();
    updateLedPositions();

    gl.glBindBuffer(GL_ARRAY_BUFFER, bufferNames.get(Buffer.VERTEX));
    gl.glBufferData(GL_ARRAY_BUFFER, vertexBuffer.capacity() * Float.BYTES, vertexBuffer, GL_STATIC_DRAW);
    int inputAttrib = gl.glGetAttribLocation(shaderProgramId, "position");
    gl.glEnableVertexAttribArray(inputAttrib);
    gl.glVertexAttribPointer(inputAttrib, 3, GL_FLOAT, false, 0, 0);

    gl.glBindBuffer(GL_ARRAY_BUFFER, bufferNames.get(Buffer.TBO));
    gl.glBufferData(GL_ARRAY_BUFFER, tfbBuffer.capacity() * Float.BYTES, tfbBuffer, GL_STATIC_READ);
    gl.glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, bufferNames.get(Buffer.TBO));

    gl.glEnable(GL_RASTERIZER_DISCARD);
    gl.glUseProgram(shaderProgramId);

    gl.glUniform1f(fTimeLoc, speed.getValuef() * (float)totalTime);
    for (String paramName : scriptParams.keySet()) {
      gl.glUniform1f(paramLocations.get(paramName), scriptParams.get(paramName).getValuef());
    }
    
    // Bind the current frame texture
    if (!frameTextures.isEmpty() && textureLoc >= 0) {
      int frameIndex = (int) frameNumber.getValue();
      frameIndex = Math.max(0, Math.min(frameIndex, frameTextures.size() - 1));
      currentFrameIndex = frameIndex;
      
      com.jogamp.opengl.util.texture.Texture currentTexture = frameTextures.get(frameIndex);
      if (currentTexture != null && GLUtil.validateTextureUnitUsage(0, textureLimits)) {
        gl.glActiveTexture(GL_TEXTURE0);
        currentTexture.enable(gl);
        currentTexture.bind(gl);
        gl.glUniform1i(textureLoc, 0); // 0 is the texture unit
        GLUtil.checkGLError(gl, "frame texture binding");
      }
    }
    
    if (audioTextureHandle[0] > 0 && fftTextureLoc >= 0) {
      if (GLUtil.validateTextureUnitUsage(1, textureLimits)) {
        gl.glActiveTexture(GL_TEXTURE1);
        gl.glBindTexture(GL_TEXTURE_2D, audioTextureHandle[0]);
        gl.glUniform1i(fftTextureLoc, 1);
        GLUtil.checkGLError(gl, "audio texture binding");
      }
    }

    gl.glBeginTransformFeedback(GL_POINTS);
    {
      gl.glDrawArrays(GL_POINTS, 0, model.points.length);
    }
    gl.glEndTransformFeedback();
    gl.glFlush();

    gl.glGetBufferSubData(GL_TRANSFORM_FEEDBACK_BUFFER, 0, tfbBuffer.capacity() * Float.BYTES, tfbBuffer);

    gl.glUseProgram(0);
    gl.glDisable(GL_RASTERIZER_DISCARD);

    CkVShader.glDrawable.getContext().release();
  }

  @Override
  public void onParameterChanged(LXParameter p) {
    if (p == this.scriptName) {
      LX.log("scriptName parameter changed!");
      reloadShader(((StringParameter)p).getString());
    }
    if (p == this.frameDir) {
      LX.log("frameDir parameter changed!");
      loadFrameSequence(((StringParameter)p).getString());
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
    // Clean up frame textures
    clearFrameTextures();
    
    // Clean up other resources
    if (gl != null) {
      CkVShader.glDrawable.getContext().makeCurrent();
      
      if (audioTextureHandle[0] > 0) {
        LX.log("Disposing audio texture");
        gl.glDeleteTextures(1, audioTextureHandle, 0);
        audioTextureHandle[0] = 0;
      }
      
      if (shaderProgramId != -1) {
        LX.log("Disposing shader program");
        gl.glDeleteProgram(shaderProgramId);
        shaderProgramId = -1;
      }
      
      GLUtil.checkGLError(gl, "resource disposal");
      CkVShader.glDrawable.getContext().release();
    }
    
    super.dispose();
  }

  public void run(double deltaMs) {
    glRun(deltaMs);
    LXPoint[] points = model.points;
    float threshold = alphaThresh.getValuef();
    for (int i = 0; i < points.length; i++) {
      float red = tfbBuffer.get(i*3);
      float green = tfbBuffer.get(i*3 + 1);
      float blue = tfbBuffer.get(i*3 + 2);
      int color = LXColor.rgbf(red, green, blue);
      float bright = LXColor.luminosity(color)/100f;
      if (bright < threshold) {
        float alpha = (bright/threshold);
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
  public void buildDeviceControls(LXStudio.UI ui, UIDevice uiDevice, CkVShaderFrames pattern) {
    int minContentWidth = 280;
    uiDevice.setContentWidth(minContentWidth);

    final UILabel fileLabel = (UILabel)
      new UILabel(0, 0, 90, 18)
        .setLabel(pattern.scriptName.getString())
        .setBackgroundColor(LXColor.BLACK)
        .setBorderRounding(4)
        .setTextAlignment(VGraphics.Align.CENTER, VGraphics.Align.MIDDLE)
        .setTextOffset(0, -1)
        .addToContainer(uiDevice);

    pattern.scriptName.addListener(p -> {
      fileLabel.setLabel(pattern.scriptName.getString());
    });

    this.openButton = (UIButton) new UIButton(95, 0, 18, 18) {
      @Override
      public void onToggle(boolean on) {
        if (on) {
          ((GLX)lx).showOpenFileDialog(
            "Open Vertex Shader",
            "Vertex Shader",
            new String[] { "vtx" },
            GLUtil.shaderDir(lx) + File.separator,
            (path) -> { onOpen(new File(path)); }
          );
        }
      }
    }
      .setIcon(ui.theme.iconOpen)
      .setMomentary(true)
      .setDescription("Open Shader")
      .addToContainer(uiDevice);

    // Frame directory selection:
    final UILabel frameDirLabel = (UILabel)
      new UILabel(118, 0, 90, 18)
        .setLabel(pattern.frameDir.getString().isEmpty() ? "No Folder" : 
                  new File(pattern.frameDir.getString()).getName())
        .setBackgroundColor(LXColor.BLACK)
        .setBorderRounding(4)
        .setTextAlignment(VGraphics.Align.CENTER, VGraphics.Align.MIDDLE)
        .setTextOffset(0, -1)
        .addToContainer(uiDevice);

    pattern.frameDir.addListener(p -> {
      String dirPath = pattern.frameDir.getString();
      frameDirLabel.setLabel(dirPath.isEmpty() ? "No Folder" : new File(dirPath).getName());
    });

    this.frameDirButton = (UIButton) new UIButton(213, 0, 18, 18) {
      @Override
      public void onToggle(boolean on) {
        if (on) {
          ((GLX)lx).showOpenFileDialog(
            "Select Any File in Frame Directory",
            "Image",
            new String[] { "png", "jpg", "jpeg" },
            GLUtil.shaderDir(lx) + File.separator + "textures" + File.separator,
            (path) -> { onOpenFrameDir(new File(path).getParentFile()); }
          );
        }
      }
    }
      .setIcon(ui.theme.iconOpen)
      .setMomentary(true)
      .setDescription("Select Frame Directory (pick any file in the directory)")
      .addToContainer(uiDevice);

    final UIButton resetButton = (UIButton) new UIButton(236, 0, 18, 18) {
      @Override
      public void onToggle(boolean on) {
        if (on) {
          lx.engine.addTask(() -> {
            logger.info("Force reloading shader (bypassing cache)");
            forceReload = true;
            reloadShader(scriptName.getString(), false);
          });
        }
      }
    }.setIcon(ui.theme.iconLoad)
      .setMomentary(true)
      .setDescription("Force reload shader (bypass cache)")
      .addToContainer(uiDevice);

    if (GLUtil.CACHING_ENABLED) {
      final UIButton clearCacheButton = (UIButton) new UIButton(259, 0, 18, 18) {
        @Override
        public void onToggle(boolean on) {
          if (on) {
            lx.engine.addTask(() -> {
              logger.info("Clearing shader cache");
              shaderCache.clearCache();
            });
          }
        }
      }.setIcon(ui.theme.iconLoad)
        .setMomentary(true)
        .setDescription("Clear shader cache")
        .addToContainer(uiDevice);
    }

    final UI2dContainer sliders = (UI2dContainer)
      UI2dContainer.newHorizontalContainer(uiDevice.getContentHeight() - 22, 2)
        .setPosition(0, 22)
        .addToContainer(uiDevice);

    final UILabel error = (UILabel)
      new UILabel(0, 22, uiDevice.getContentWidth(), uiDevice.getContentHeight() - 22)
        .setBreakLines(true)
        .setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.TOP)
        .addToContainer(uiDevice)
        .setVisible(false);

    // Add sliders to container on every reload
    pattern.onReload.addListener(p -> {
      sliders.removeAllChildren();
      // Only add built-in sliders if they're not already present as ISF parameters
      if (!pattern.scriptParams.containsKey("alfTh")) {
        new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, alphaThresh)
          .addToContainer(sliders);
      }
      if (!pattern.scriptParams.containsKey("speed")) {
        new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, speed)
          .addToContainer(sliders);
      }
      new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, frameNumber)
        .addToContainer(sliders);
      for (CompoundParameter slider : pattern.scriptParams.values()) {
        new UISlider(UISlider.Direction.VERTICAL, 40, sliders.getContentHeight() - 14, slider)
          .addToContainer(sliders);
      }
      float contentWidth = LXUtils.maxf(minContentWidth, sliders.getContentWidth());
      uiDevice.setContentWidth(contentWidth);
      error.setWidth(contentWidth);
    }, true);

    pattern.error.addListener(p -> {
      String str = pattern.error.getString();
      boolean hasError = (str != null && !str.isEmpty());
      error.setLabel(hasError ? str : "");
      error.setVisible(hasError);
      sliders.setVisible(!hasError);
    }, true);
  }

  public void onOpen(final File openFile) {
    this.openButton.setActive(false);
    if (openFile != null) {
      LX lx = getLX();
      String baseFilename = openFile.getName().substring(0, openFile.getName().indexOf('.'));
      LX.log("Loading: " + baseFilename);

      lx.engine.addTask(() -> {
        LX.log("Running script name setting task");
        lx.command.perform(new LXCommand.Parameter.SetString(
          scriptName,
          baseFilename
        ));
      });
    }
  }
  
  public void onOpenFrameDir(final File directory) {
    this.frameDirButton.setActive(false);
    if (directory != null && directory.isDirectory()) {
      LX lx = getLX();
      String dirPath = directory.getAbsolutePath();
      LX.log("Loading frame directory: " + dirPath);

      lx.engine.addTask(() -> {
        LX.log("Running frame directory setting task");
        lx.command.perform(new LXCommand.Parameter.SetString(
          frameDir,
          dirPath
        ));
      });
    }
  }
}