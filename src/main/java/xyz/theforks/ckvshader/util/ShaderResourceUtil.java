package xyz.theforks.ckvshader.util;

import heronarts.lx.LX;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class ShaderResourceUtil {

    public static boolean VERBOSE = false;
    public static boolean resourceFilesCopied = false;

    /**
     * Export default shaders from JAR resources to ~/Chromatik/CkVShader/
     * This should be called when the OpenGL context is initialized.
     */
    public static void exportDefaultShaders(LX lx) {
        if (resourceFilesCopied) {
            return;
        }
        LX.log("Exporting default shaders for CkVShader");
        
        // Get the shader directory path
        String shaderDir = GLUtil.shaderDir(lx);
        LX.log("Target shader directory: " + shaderDir);
        File shaderDirFile = new File(shaderDir);
        if (!shaderDirFile.exists()) {
            LX.log("Creating shader directory: " + shaderDir);
            shaderDirFile.mkdirs();
        }

        // Export shader files - look for resources under data/shaders path
        // First get a known working resource URL from our JAR
        LX.log("Looking for shader resources...");
        URL texturesDirUrl = ShaderResourceUtil.class.getClassLoader().getResource("data/shaders/textures");
        if (texturesDirUrl == null) {
            LX.log("data/shaders/textures resource not found, checking data/shaders...");
            texturesDirUrl = ShaderResourceUtil.class.getClassLoader().getResource("data/shaders");
        }
        
        // Now construct the shaders URL from the same JAR
        List<String> includedShaders = new ArrayList<>();
        if (texturesDirUrl != null) {
            LX.log("Found resource URL: " + texturesDirUrl.toString());
            if (texturesDirUrl.toString().contains("ckvshader")) {
                String baseJarUrl = texturesDirUrl.toString().replace("data/shaders/textures", "data/shaders");
                LX.log("Constructed base JAR URL: " + baseJarUrl);
                try {
                    URL shadersUrl = new URL(baseJarUrl);
                    includedShaders = listResourceFilesFromUrl(shadersUrl, "data/shaders", Arrays.asList(".vtx", ".vti"));
                } catch (Exception e) {
                    LX.log("Error listing shaders from URL: " + e.getMessage());
                }
            } else {
                LX.log("Resource URL does not contain 'ckvshader', skipping custom logic");
            }
        } else {
            LX.log("No shader resource URL found");
        }
        
        if (includedShaders.isEmpty()) {
            // Fallback to original method
            includedShaders = getIncludedShaderFiles(ShaderResourceUtil.class, "data/shaders");
        }
        
        LX.log("Found " + includedShaders.size() + " shader files to export");
        for (String shader : includedShaders) {
            LX.log("  - " + shader);
        }
        
        for (String includedShader : includedShaders) {
            File shaderFile = new File(shaderDir + File.separator + includedShader);
            if (!shaderFile.exists()) {
                LX.log("Exporting CkVShader shader: " + includedShader);
                try {
                    copyResourceToFile(ShaderResourceUtil.class, "data/shaders/" + includedShader, shaderFile);
                    LX.log("Successfully exported: " + includedShader);
                } catch (Exception e) {
                    LX.log("Error copying shader file " + includedShader + ": " + e.getMessage());
                }
            }
        }

        // Export texture files if they exist
        String textureDir = shaderDir + File.separator + "textures";
        File textureDirFile = new File(textureDir);
        if (!textureDirFile.exists()) {
            textureDirFile.mkdirs();
        }

        List<String> includedTextures = getIncludedTextureFiles(ShaderResourceUtil.class, "data/shaders/textures");
        for (String includedTexture : includedTextures) {
            File textureFile = new File(textureDir + File.separator + includedTexture);
            if (!textureFile.exists()) {
                LX.log("Exporting CkVShader texture: " + includedTexture);
                try {
                    copyResourceToFile(ShaderResourceUtil.class, "data/shaders/textures/" + includedTexture, textureFile);
                } catch (Exception e) {
                    LX.log("Error copying texture file: " + e.getMessage());
                }
            }
        }

        resourceFilesCopied = true;
    }

    /**
     * Get list of shader files included in the JAR resources
     */
    public static List<String> getIncludedShaderFiles(Class<?> clazz, String resourcePath) {
        return listResourceFiles(clazz, resourcePath, Arrays.asList(".vtx", ".vti"));
    }

    /**
     * Get list of texture files included in the JAR resources
     */
    public static List<String> getIncludedTextureFiles(Class<?> clazz, String resourcePath) {
        return listResourceFiles(clazz, resourcePath, Arrays.asList(".png", ".jpg", ".jpeg"));
    }

    /**
     * List resource files from a specific URL
     */
    public static List<String> listResourceFilesFromUrl(URL resourceUrl, String resourcePath, List<String> extensions) {
        try {
            
            if (resourceUrl.getProtocol().equals("jar")) {
                // Handle resources in JAR
                try (FileSystem fileSystem = FileSystems.newFileSystem(
                        resourceUrl.toURI(), Collections.<String, Object>emptyMap())) {
                    Path path = fileSystem.getPath(resourcePath);
                    List<String> result = listFiles(path, extensions);
                    return result;
                }
            } else {
                // Handle resources in regular directory
                Path path = Path.of(resourceUrl.toURI());
                List<String> result = listFiles(path, extensions);
                return result;
            }
        } catch (URISyntaxException | IOException e) {
            LX.log("Error listing resource files from URL: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * List resource files with optional file extension filtering
     */
    public static List<String> listResourceFiles(Class<?> clazz, String resourcePath, List<String> extensions) {
        try {
            URL resourceUrl = clazz.getClassLoader().getResource(resourcePath);
            
            if (resourceUrl == null) {
                return Collections.emptyList();
            }


            if (resourceUrl.getProtocol().equals("jar")) {
                // Handle resources in JAR
                try (FileSystem fileSystem = FileSystems.newFileSystem(
                        resourceUrl.toURI(), Collections.<String, Object>emptyMap())) {
                    Path path = fileSystem.getPath(resourcePath);
                    List<String> result = listFiles(path, extensions);
                    return result;
                }
            } else {
                // Handle resources in regular directory (useful for development)
                Path path = Path.of(resourceUrl.toURI());
                List<String> result = listFiles(path, extensions);
                return result;
            }
        } catch (URISyntaxException | IOException e) {
            LX.log("Error listing resource files: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * List files in a directory with optional extension filtering
     */
    private static List<String> listFiles(Path path, List<String> extensions) throws IOException {
        List<String> fileList = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(path, 1)) {
            walk.filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        String fileName = filePath.getFileName().toString();
                        if (extensions == null || extensions.isEmpty()) {
                            fileList.add(fileName);
                        } else {
                            // Check if file has one of the allowed extensions
                            for (String ext : extensions) {
                                if (fileName.toLowerCase().endsWith(ext.toLowerCase())) {
                                    fileList.add(fileName);
                                    break;
                                }
                            }
                        }
                    });
        }

        return fileList;
    }

    /**
     * Copy a file from the resources path of a JAR to a file on disk.
     */
    public static void copyResourceToFile(Class<?> clazz, String resourcePath, File targetFile) {
        try {
            URL resourceUrl = clazz.getClassLoader().getResource(resourcePath);
            if (resourceUrl == null) {
                LX.log("Resource not found: " + resourcePath);
                return;
            }

            if (resourceUrl.getProtocol().equals("jar")) {
                // Handle resources in JAR
                try (FileSystem fileSystem = FileSystems.newFileSystem(
                        resourceUrl.toURI(), Collections.<String, Object>emptyMap())) {
                    Path path = fileSystem.getPath(resourcePath);
                    Files.copy(path, targetFile.toPath());
                }
            } else {
                // Handle resources in regular directory (useful for development)
                Path path = Path.of(resourceUrl.toURI());
                Files.copy(path, targetFile.toPath());
            }
        } catch (URISyntaxException | IOException e) {
            LX.log("Error copying resource file: " + e.getMessage());
        }
    }

    /**
     * Force re-export of resources (useful for development/testing)
     */
    public static void forceReexport(LX lx) {
        resourceFilesCopied = false;
        exportDefaultShaders(lx);
    }
}