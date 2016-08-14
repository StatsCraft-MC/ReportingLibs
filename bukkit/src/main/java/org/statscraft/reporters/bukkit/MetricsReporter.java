/*
 * Copyright (c) 2016 StatsCraft Authors and Contributors.
 */

package org.statscraft.reporters.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;

/**
 * Metrics reporting service class
 * www.statscraft.org
 */
public class MetricsReporter {
    /*
     * Constants
     */

    // Api
    private static final int API_VERSION = 1;
    private static final String API_BASE_URL = "http://api.statscraft.org/v" + API_VERSION + "/report";
    private static final String API_PLUGIN_URL = API_BASE_URL + "/plugin";
    private static final String API_SERVER_URL = API_BASE_URL + "/server";
    private static final String API_UPDATE_URL = API_BASE_URL + "/update";

    // System properties
    private static final String PROP_PREFIX = "org.statscraft.reporters.bukkit.";
    private static final String PROP_CURR = PROP_PREFIX + "currentReporter";
    private static final String PROP_RV_PREFIX = PROP_PREFIX + "reporterversion.";

    // Custom data limits
    private static final int MAX_CUSTOMDATA_COUNT = 15;
    private static final int MAX_CUSTOMDATA_KEY_LENGTH = 30;
    private static final int MAX_CUSTOMDATA_VALUE_LENGTH = 100;

    // Update task interval, in ticks
    private static final int UPDATE_DELAY = 10 * 60 * 20;

    // Config path
    private static final String FOLDER_NAME = "StatsCraft";
    private static final String CONFIG_FILENAME = "config.yml";

    /*
     * Instances
     */
    private final Server server;
    private final BukkitScheduler scheduler;
    private final Plugin plugin;

    /*
     * Variables
     */
    private final String authKey;
    private static boolean started;
    private MetricsConfig config;
    private final boolean dynamicPluginData;
    private Map<String, String> customData;

    /**
     * Constructor of the service
     *
     * @param plugin the plugin instance
     * @param authKey the plugin's authKey
     */
    public MetricsReporter(Plugin plugin, String authKey) {
        this(plugin, authKey, false);
    }

    public MetricsReporter(Plugin plugin, String authKey, boolean dynamicPluginData) {
        // Get instances
        this.plugin = plugin;
        this.server = this.plugin.getServer();
        this.scheduler = this.server.getScheduler();
        // Set variables
        this.authKey = authKey;
        started = false;
        this.dynamicPluginData = dynamicPluginData;
        customData = new HashMap<>();
    }

    /**
     * Method that adds custom data to send with the plugin metrics
     *
     * @param key the name of the custom data
     * @param value the value of the custom data
     *
     * @throws IllegalStateException if the plugin has reached the maximum amount of customdata entries
     * @throws IllegalArgumentException if the customdata name or value is invalid
     */
    public MetricsReporter addCustomData(String key, Object value) throws IllegalStateException, IllegalArgumentException {
        if (started) {
            throw new IllegalStateException("Can't add custom data when the metrics service is running!");
        }
        if (customData.size() > MAX_CUSTOMDATA_COUNT) {
            throw new IllegalStateException("Reached the maximum count of custom data!");
        }
        if (key.length() > MAX_CUSTOMDATA_KEY_LENGTH) {
            throw new IllegalArgumentException("The custom data key can't be longer than "
                + MAX_CUSTOMDATA_KEY_LENGTH + " characters!");
        }
        String valueStr = String.valueOf(value);
        if (valueStr.length() > MAX_CUSTOMDATA_VALUE_LENGTH) {
            throw new IllegalArgumentException("The custom data value can't be longer than "
                + MAX_CUSTOMDATA_VALUE_LENGTH + " characters!");
        }
        customData.put(key, valueStr);
        return this;
    }

    /**
     * Method that starts the metrics service
     *
     * @return true if the service started successfully
     */
    public boolean start() {
        // Stop if the metrics service its already running
        if (started) {
            return true; //TODO should we really return true here?
        }

        // Ignore if the plugin is disabled
        if (!plugin.isEnabled()) {
            return false;
        }

        // Load config
        config = new MetricsConfig().load();

        // Stop if metrics are disabled in the config
        if (config.isOptOut()) {
            return false;
        }

        // Prepare the system property to elect the main service
        System.setProperty(PROP_RV_PREFIX + plugin.getName(), Integer.toString(API_VERSION));
        // Register an event to update the main service
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginDisable(PluginDisableEvent event) {
                System.clearProperty(PROP_RV_PREFIX + event.getPlugin().getName());
                String curr = System.getProperty(PROP_CURR);
                if (!curr.equals(event.getPlugin().getName())) {
                    return;
                }
                System.setProperty(PROP_CURR, "");
                checkNewerVersionOrElectMe();
            }
        }, plugin);

        // Send plugin data async
        scheduler.runTaskAsynchronously(plugin, new PluginReportTask());

        // Schedule post server boot task
        scheduler.scheduleSyncDelayedTask(plugin, new Runnable() {
            @Override
            public void run() {
                // If the plugin was disabled do nothing
                if (!plugin.isEnabled()) {
                    return;
                }

                // Look for the main daemon
                checkNewerVersionOrElectMe();

                // If this is the main daemon, schedule the server data sender
                if (System.getProperty(PROP_CURR).equals(plugin.getName())) {
                    scheduler.runTaskAsynchronously(plugin, new ServerReportTask());
                }

                // Schedule server update task
                scheduler.runTaskTimerAsynchronously(plugin, new ServerUpdateTask(), 0, UPDATE_DELAY);

                if (dynamicPluginData) {
                    // Schedule plugin update task
                    scheduler.runTaskTimerAsynchronously(plugin, new PluginUpdateTask(), 0, UPDATE_DELAY);
                }
            }
        });

        // Everything ok, mark the service as started
        started = true;
        return true;
    }

    /*
     * Method used to check the newest daemon version
     */
    private void checkNewerVersionOrElectMe() {
        for (Plugin pl : server.getPluginManager().getPlugins()) {
            Integer ver = Integer.getInteger(PROP_RV_PREFIX + pl.getName());
            if (pl.isEnabled() && ver != null && ver > API_VERSION) {
                return;
            }
        }
        System.setProperty(PROP_CURR, plugin.getName());
    }

    /*
     * Common method to send the json data to the REST API
     */
    private void sendJson(String url, String json) throws IOException {
        // Create connection
        final URLConnection connection = new URL(url).openConnection();
        // Prepare data
        final byte[] bytes = json.getBytes();
        // Connection parameters
        connection.addRequestProperty("User-Agent", "StatsCraft/" + API_VERSION);
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Content-Length", Integer.toString(bytes.length));
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.setDoOutput(true);
        // Write data
        final OutputStream out = connection.getOutputStream();
        out.write(bytes);
        out.flush();
        out.close();
        // Get response
        final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        final String response = reader.readLine();
        reader.close();
        // Check response
        if (response == null) {
            throw new IOException("Response was null");
        }
        if (response.startsWith("ERR")) {
            throw new IOException(response);
        }
    }

    /*
     * Task used to send the plugin related data
     */
    private class PluginReportTask implements Runnable {
        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                return;
            }

            PluginDescriptionFile pluginInfo = plugin.getDescription();
            String name = pluginInfo.getFullName();
            String version = pluginInfo.getVersion();
            String description = pluginInfo.getDescription();
            String website = pluginInfo.getWebsite();
            List<String> authors = pluginInfo.getAuthors();
            List<String> depend = pluginInfo.getDepend();
            List<String> softDepend = pluginInfo.getSoftDepend();

            NJson json = new NJson()
                .put("authKey", authKey)
                .put("serverUuid", config.getUuid())
                .put("name", name)
                .put("version", version)
                .put("description", description)
                .put("website", website)
                .putArray("authors", authors)
                .putArray("depend", depend)
                .putArray("softDepend", softDepend)
                .putMap("customData", customData);

            //make ready for next cycle or just free memory if dynamic plugin data is disabled
            customData.clear();

            // Send the data
            try {
                sendJson(API_PLUGIN_URL, json.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /*
     * Task used to send the server related data
     */
    private class ServerReportTask implements Runnable {
        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                return;
            }
            if (!System.getProperty(PROP_CURR).equals(plugin.getName())) {
                return;
            }

            // Os data
            String osName = System.getProperty("os.name");
            String osArch = System.getProperty("os.arch");
            String osVersion = System.getProperty("os.version");

            // Jvm data
            String javaVmName = System.getProperty("java.vm.name");
            String javaVendor = System.getProperty("java.vendor");
            String javaVersion = System.getProperty("java.version");

            // Runtime data
            Runtime runtime = Runtime.getRuntime();
            int coreCount = runtime.availableProcessors();
            long maxJvmRam = runtime.maxMemory();

            // Server instance data
            String serverVersion = server.getBukkitVersion();
            String minecraftVersion = server.getVersion();
            boolean onlineMode = server.getOnlineMode();
            int worldsCount = server.getWorlds().size();
            int pluginsCount = server.getPluginManager().getPlugins().length;
            String defaultGamemode = server.getDefaultGameMode().toString();

            NJson json = new NJson()
                .put("uuid", config.getUuid())
                .put("osName", osName)
                .put("osArch", osArch)
                .put("osVersion", osVersion)
                .put("javaVmName", javaVmName)
                .put("javaVendor", javaVendor)
                .put("javaVersion", javaVersion)
                .put("coreCount", Integer.toString(coreCount))
                .put("maxJvmRam", Long.toString(maxJvmRam))
                .put("serverVersion", serverVersion)
                .put("minecraftVersion", minecraftVersion)
                .put("onlineMode", Boolean.toString(onlineMode))
                .put("worldsCount", Integer.toString(worldsCount))
                .put("pluginsCount", Integer.toString(pluginsCount))
                .put("defaultGamemode", defaultGamemode);

            // Send the data
            try {
                sendJson(API_SERVER_URL, json.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /*
     * Task used to send the update data
     */
    private class ServerUpdateTask implements Runnable {
        @Override
        public void run() {
            if (!plugin.isEnabled()) {
                return;
            }
            if (!System.getProperty(PROP_CURR).equals(plugin.getName())) {
                return;
            }
            NJson json = new NJson()
                .put("uuid", config.getUuid())
                .put("playerCount", Integer.toString(getOnlinePlayers()));
            try {
                sendJson(API_UPDATE_URL, json.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Compatible with 1.7 and 1.8+
        private int getOnlinePlayers() {
            int online = -1;
            try {
                online = Bukkit.getOnlinePlayers().size();
            } catch (NoSuchMethodError ignored) {
                try {
                    online = ((Player[]) Bukkit.class.getMethod("getOnlinePlayers").invoke(null)).length;
                } catch (ReflectiveOperationException e) {
                    e.printStackTrace();
                }
            }
            return online;
        }
    }

    private class PluginUpdateTask implements Runnable {
        @Override
        public void run() {
            //Call event
            Bukkit.getPluginManager().callEvent(new MetricsReportEvent(customData));

            // Custom data
            List<String> customDataList = new ArrayList<>();
            for (Map.Entry<String, String> entry : customData.entrySet()) {
                customDataList.add(new NJson().put(entry.getKey(), entry.getValue()).toString());
            }
            //make ready for next cycle or just free memory ifdynamic plugin data is disabled
            customData.clear();

            //TODO send the data
        }
    }

    public static class MetricsReportEvent extends Event {
        private final Map<String, String> data;

        private static HandlerList handlerList = new HandlerList();

        public MetricsReportEvent(Map<String, String> data) {
            this.data = data;
        }

        public Map<String, String> getData() {
            return data;
        }

        @Override
        public HandlerList getHandlers() {
            return handlerList;
        }

        public static HandlerList getHandlerList() {
            return handlerList;
        }
    }

    /*
     * The metrics configuration manager
     */
    private class MetricsConfig {
        private final File configFile;
        private FileConfiguration config;

        private MetricsConfig() {
            File pluginFolder = plugin.getDataFolder().getParentFile();
            File metricsFolder = new File(pluginFolder, FOLDER_NAME);
            configFile = new File(metricsFolder, CONFIG_FILENAME);
        }

        private MetricsConfig load() {
            config = YamlConfiguration.loadConfiguration(configFile);
            return validate();
        }

        private MetricsConfig validate() {
            boolean changes = false;
            if (!config.isString("uuid")) {
                config.set("uuid", UUID.randomUUID());
                changes = true;
            }
            // Validate the UUID string
            try {
                UUID.fromString(config.getString("uuid"));
            } catch (IllegalArgumentException ignore) {
                config.set("uuid", UUID.randomUUID());
                changes = true;
            }
            if (!config.isBoolean("opt-out")) {
                config.set("opt-out", false);
                changes = true;
            }
            if (changes) {
                save();
            }
            return this;
        }

        private MetricsConfig save() {
            try {
                config.save(configFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return this;
        }

        private String getUuid() {
            return config.getString("uuid");
        }

        private boolean isOptOut() {
            return config.getBoolean("opt-out");
        }
    }

    /*
     * Small Json builder
     */
    private static class NJson {
        private StringBuilder builder;

        private NJson() {
            builder = new StringBuilder();
        }

        private NJson put(String key, String value) {
            builder.append(putOuterMapping(prepare(key), "\"" + prepare(value) + "\"").toString());
            return this;
        }

        private NJson putArray(String key, List<String> values) {
            String array = array(values);
            builder.append(putOuterMapping(prepare(key), array).toString());
            return this;
        }

        private NJson putMap(String key, Map<String, String> values) {
            String map = map(values);
            StringBuilder b = putOuterMapping(prepare(key), map);
            builder.append(b.toString());
            return this;
        }

        @Override
        public String toString() {
            return "{" + builder.toString() + "}";
        }

        //internal functions
        private String map(Map<String, String> values) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (b.length() > 0) {
                    b.append(",");
                }
                b.append("\"").append(prepare(entry.getKey())).append("\":\"").append(prepare(entry.getValue())).append("\"");
            }
            return "{" + b.toString() + "}";
        }

        private StringBuilder putOuterMapping(String key, String value) {
            StringBuilder b = new StringBuilder();
            if (builder.length() > 0) {
                b.append(",");
            }
            b.append("\"").append(key).append("\":").append(value);
            return b;
        }

        private String array(List<String> elements) {
            StringBuilder b = new StringBuilder();
            for (String e : elements) {
                if (b.length() > 0) {
                    b.append(",");
                }
                b.append("\"").append(prepare(e)).append("\"");
            }
            return "[" + b.toString() + "]";
        }

        private String prepare(String input) {
            return input.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
