/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.plugin.layer.internal;

import dev.ikm.plugin.layer.IkeServiceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The Layers class represents a system of module layers used to manage plugins in an application.
 * It provides functionality for setting up and configuring the layers, deploying plugins, and handling
 * directory change events.
 */
public class Layers {
    private static final Logger LOG = LoggerFactory.getLogger(Layers.class);
    private static final Pattern PLUGIN_ARTIFACT_PATTERN = Pattern.compile("(.*?)\\-(\\d[\\d+\\-_A-Za-z\\.]*?)\\.(jar|zip|tar|tar\\.gz)");
    public static final String TINKAR_PLUGINS_TEMP_DIR = "tinkar-plugins";
    public static final String BOOT_LAYER = "boot-layer";
    public static final String PLUGIN_LAYER = "plugin-layer";

    private static final List<ModuleLayer> PLUGIN_PARENT_LAYER_AS_LIST = List.of(ModuleLayer.boot());
    /**
     * The actual module layers by name.
     */
    private final CopyOnWriteArraySet<PluginNameAndModuleLayer> moduleLayers = new CopyOnWriteArraySet<>();
    private final PluginNameAndModuleLayer bootLayer;

    /**
     * Temporary directory where all plug-ins will be copied to. Modules will be
     * sourced from there, allowing to remove plug-ins by deleting their original
     * directory.
     */
    private final Path pluginsWorkingDir;

    /**
     * All configured directories potentially containing plug-ins.
     */
    private final Set<PluginWatchDirectory> pluginsDirectories;


    private int pluginIndex = 0;

    /**
     * Creates a new instance of Layers.
     *
     * @param pluginsDirectories a set of PluginsDirectory objects representing the directories where plugins are stored
     */
    public Layers(Set<PluginWatchDirectory> pluginsDirectories) {
        this.bootLayer = new PluginNameAndModuleLayer(BOOT_LAYER, ModuleLayer.boot());
        this.moduleLayers.add(bootLayer);
        this.pluginsDirectories = Collections.unmodifiableSet(pluginsDirectories);

        try {
            this.pluginsWorkingDir = Files.createTempDirectory(TINKAR_PLUGINS_TEMP_DIR);

            if (!pluginsDirectories.isEmpty()) {
                for (PluginWatchDirectory pluginWatchDirectory : pluginsDirectories) {
                    handlePluginComponent(pluginWatchDirectory);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<ModuleLayer> getModuleLayers() {
        return moduleLayers.stream().map(pluginNameAndModuleLayer -> pluginNameAndModuleLayer.moduleLayer()).toList();
    }

    /**
     * Handles the pluginWatchDirectory component by creating module layer for each pluginWatchDirectory artifact found in the directory.
     *
     * @param pluginWatchDirectory the pluginWatchDirectory object representing the pluginWatchDirectory component
     * @return a map of pluginWatchDirectory names and associated module layers
     * @throws IOException if an I/O error occurs while handling the pluginWatchDirectory component
     */
    private void handlePluginComponent(PluginWatchDirectory pluginWatchDirectory) throws IOException {
        LOG.info("Processing plugin directory: {}", pluginWatchDirectory.directory().toAbsolutePath());
        
        // alternative, create a new layer out of all plugins...
        Layers.this.moduleLayers.clear();
        Layers.this.moduleLayers.add(bootLayer);
        List<Path> pluginPathEntries = getPluginPathEntries(pluginWatchDirectory);
        
        LOG.info("Found {} plugin JAR files in directory", pluginPathEntries.size());
        for (Path pluginPath : pluginPathEntries) {
            LOG.info("  - Plugin JAR: {}", pluginPath.toAbsolutePath());
        }
        
        if (pluginPathEntries.isEmpty()) {
            LOG.warn("No plugin JARs found in: {}", pluginWatchDirectory.directory().toAbsolutePath());
            return;
        }
        
        ModuleLayer pluginModuleLayer = createModuleLayer(PLUGIN_PARENT_LAYER_AS_LIST, pluginPathEntries);
        PluginNameAndModuleLayer pluginNameAndModuleLayer = new PluginNameAndModuleLayer(pluginWatchDirectory.name(), pluginModuleLayer);
        moduleLayers.add(pluginNameAndModuleLayer);
        
        // Log the modules in the new layer
        LOG.info("Created plugin module layer '{}' with {} modules:", 
                pluginWatchDirectory.name(), pluginModuleLayer.modules().size());
        for (Module module : pluginModuleLayer.modules()) {
            LOG.info("  - Module: {} (descriptor: {})", 
                    module.getName(), 
                    module.getDescriptor() != null ? module.getDescriptor().toNameAndVersion() : "none");
        }
        
        // Create new service loader with new layer...
        IkeServiceManager.deployPluginServiceLoader(moduleLayers.stream().map(pluginNameAndModuleLayerFromStream -> pluginNameAndModuleLayerFromStream.moduleLayer()).toList());
    }

    private static List<Path> getPluginPathEntries(PluginWatchDirectory pluginWatchDirectory) {
        return getPluginPathEntries(pluginWatchDirectory.directory().toFile(), new ArrayList<>());
    }
    
    private static List<Path> getPluginPathEntries(File directory, List<Path> pluginPathEntries) {
        File[] files = directory.listFiles();
        if (files == null) {
            LOG.warn("Cannot list files in directory: {}", directory);
            return pluginPathEntries;
        }
        
        for (File jarFile : files) {
            if (jarFile.getName().endsWith(".jar")) {
                pluginPathEntries.add(jarFile.toPath());
                LOG.debug("Found plugin JAR: {}", jarFile.getName());
            } else if (jarFile.isDirectory()) {
                getPluginPathEntries(jarFile, pluginPathEntries);
            }
        }
        return pluginPathEntries;
    }

    /**
     * Computes the plugin name based on the given pluginWatchDirectory and path.
     *
     * @param pluginWatchDirectory the PluginWatchDirectory object representing the pluginWatchDirectory component
     * @param path                 the path of the plugin artifact
     * @return an Optional containing the plugin name, or an empty Optional if the plugin artifact does not match the expected pattern
     */
    private Optional<String> pluginName(PluginWatchDirectory pluginWatchDirectory, Path path) {
        Matcher matcher = PLUGIN_ARTIFACT_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String pluginArtifactId = matcher.group(1);
        String pluginVersion = matcher.group(2);
        String derivedFrom = pluginWatchDirectory.directory().getFileName().toString();
        String pluginName = String.join("-", derivedFrom, pluginArtifactId, pluginVersion);
        //return Optional.of(pluginName);
        return Optional.of(pluginArtifactId); //TODO simplifying the key for now. Assume we never try and have two versions of the same plugin.
    }

    /**
     * Creates a module layer with the given parent layers and module path entries.
     *
     * @param parentLayers      the list of parent module layers
     * @param modulePathEntries the list of module path entries
     * @return the created module layer
     */
    public static ModuleLayer createModuleLayer(List<ModuleLayer> parentLayers, List<Path> modulePathEntries) {
        LOG.info("Creating module layer from {} path entries", modulePathEntries.size());
        for (Path entry : modulePathEntries) {
            LOG.info("  Module path entry: {}", entry);
        }

        ClassLoader scl = ClassLoader.getSystemClassLoader();

        ModuleFinder finder = ModuleFinder.of(modulePathEntries.toArray(Path[]::new));

        Set<String> roots = finder.findAll()
                .stream()
                .map(m -> m.descriptor().name())
                .collect(Collectors.toSet());

        LOG.info("ModuleFinder discovered {} modules:", roots.size());
        for (String moduleName : roots) {
            LOG.info("  - Module: {}", moduleName);
        }

        if (roots.isEmpty()) {
            LOG.error("No modules found in the provided paths! Check if JARs have valid module-info.class");
            return null;
        }

        try {
            Configuration appConfig = Configuration.resolve(
                    finder,
                    parentLayers.stream().map(ModuleLayer::configuration).collect(Collectors.toList()),
                    ModuleFinder.of(),
                    roots);

            LOG.info("Module configuration resolved with {} modules", appConfig.modules().size());
            for (var resolvedModule : appConfig.modules()) {
                LOG.info("  - Resolved module: {}", resolvedModule.name());
                resolvedModule.reference().descriptor().provides().forEach(provides -> {
                    LOG.info("    Provides service: {} with implementations: {}",
                            provides.service(),
                            String.join(", ", provides.providers()));
                });
            }

            ModuleLayer layer = ModuleLayer.defineModulesWithOneLoader(appConfig, parentLayers, scl).layer();
            LOG.info("Created module layer with {} modules", layer.modules().size());

            return layer;
        } catch (Exception e) {
            LOG.error("Failed to create module layer", e);
            LOG.error("Available modules in parent layers:");
            for (ModuleLayer parentLayer : parentLayers) {
                LOG.error("  Parent layer has {} modules:", parentLayer.modules().size());
                for (Module m : parentLayer.modules()) {
                    LOG.error("    - {}", m.getName());
                }
            }
            throw new RuntimeException("Failed to create module layer for plugins", e);
        }
    }
    /**
     * Unpacks a plugin artifact to the target directory.
     *
     * @param pluginArtifact the path of the plugin artifact to unpack
     * @param targetDir      the directory to unpack the plugin artifact to
     * @return a list containing the target directory
     * @throws UnsupportedOperationException if the plugin artifact has an unsupported file extension
     */
    private List<Path> copyPluginArtifact(Path pluginArtifact, Path targetDir) {
        String fileName = pluginArtifact.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            Path dest = targetDir.resolve(fileName);
            try {
                Files.createDirectories(dest.getParent());
                Files.copy(pluginArtifact, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else if (fileName.endsWith(".zip")) {
            throw new UnsupportedOperationException("Can't handle .zip");
        } else if (fileName.endsWith(".tar")) {
            throw new UnsupportedOperationException("Can't handle .tar");
        } else if (fileName.endsWith(".tar.gz")) {
            throw new UnsupportedOperationException("Can't handle .tar.gz");
        }

        return List.of(targetDir);
    }

}
