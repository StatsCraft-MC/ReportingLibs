package org.statscraft.reporters.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

public class MetricsReporter {
    // Constants
    private static final int API_VERSION = 1;
    private static final String API_BASE_URL = "http://api.statscraft.org/v" + API_VERSION + "/report";
    private static final String API_PLUGIN_URL = API_BASE_URL + "/plugin";
    private static final String API_SERVER_URL = API_BASE_URL + "/server";
    private static final String API_UPDATE_URL = API_BASE_URL + "/update";

    private static final String PROP_PREFIX = "org.statscraft.reporters.bukkit.";
    private static final String PROP_CURR = PROP_PREFIX + "currentReporter";
    private static final String PROP_RV_PREFIX = PROP_PREFIX + "reporterversion.";

    private static final int MAX_CUSTOMDATA_COUNT = 15;
    private static final int MAX_CUSTOMDATA_KEY_LENGTH = 30;
    private static final int MAX_CUSTOMDATA_VALUE_LENGTH = 100;

    private static final int UPDATE_DELAY = 10 * 60 * 20; // Ticks

    private static final String FOLDER_NAME = "StatsCraft";
    private static final String CONFIGFILE_NAME = "config.yml";

    // Instances
    private final Server server;
    private final BukkitScheduler scheduler;
    private final Plugin plugin;

    // Key
    private final String authKey;

    // Config
    private MetricsConfig config;

    // Status
    private boolean started;

    // Custom data
    private Map<String, String> customData;

    public MetricsReporter(Plugin plugin, String authKey) {
        // Get instances
        this.plugin = plugin;
        this.server = this.plugin.getServer();
        this.scheduler = this.server.getScheduler();
        // Key
        this.authKey = authKey;
        // Reset status
        started = false;
        // Custom data
        customData = new HashMap<>();
    }

    public void addCustomData(String key, String value) {
        if (started) {
            throw new IllegalStateException("Can't add custom data when the metrics service is running!");
        }
        if (customData.size() == MAX_CUSTOMDATA_COUNT) {
            throw new IllegalStateException("Reached the maximum count of custom data!");
        }
        if (key.length() > MAX_CUSTOMDATA_KEY_LENGTH) {
            throw new IllegalStateException("The custom data key can't be longer than "
                + MAX_CUSTOMDATA_KEY_LENGTH + " characters!");
        }
        if (key.length() > MAX_CUSTOMDATA_VALUE_LENGTH) {
            throw new IllegalStateException("The custom data value can't be longer than "
                + MAX_CUSTOMDATA_VALUE_LENGTH + " characters!");
        }
        customData.put(key, value);
    }

    public boolean start() {
        // Stop if the metrics service is already running
        if (started) {
            return true;
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

        System.setProperty(PROP_RV_PREFIX + plugin.getName(), Integer.toString(API_VERSION));
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
                checkNewerVersionOrElectMe();

                // Schedule the server data sender
                if (System.getProperty(PROP_CURR).equals(plugin.getName())) {
                    scheduler.runTaskAsynchronously(plugin, new ServerReportTask());
                }

                // Schedule server update task
                scheduler.runTaskTimerAsynchronously(plugin, new ServerUpdateTask(), 0, UPDATE_DELAY);
            }
        });

        // Everything ok
        started = true;
        return true;
    }

    private void checkNewerVersionOrElectMe() {//go only on if there's no reporter with a newer version
        for (Plugin pl : server.getPluginManager().getPlugins()) {
            Integer ver = Integer.getInteger(PROP_RV_PREFIX + pl.getName());
            if (pl.isEnabled() && ver != null && ver > API_VERSION) {
                return;
            }
        }
        System.setProperty(PROP_CURR, plugin.getName());
    }

    private void sendJson(String url, String json) throws IOException {
        // Create connection
        final URLConnection connection = new URL(url).openConnection();
        // Prepare data
        final byte[] bytes = json.getBytes();
        // Connection parameters TODO: use costants?
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
                .putArray("softDepend", softDepend);

            // Custom data
            List<String> customDataList = new ArrayList<>();
            for (Map.Entry<String, String> entry : customData.entrySet()) {
                customDataList.add(new NJson().put(entry.getKey(), entry.getValue()).toString());
            }

            json.putArray("customData", customDataList);

            // Send the data
            try {
                sendJson(API_PLUGIN_URL, json.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

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

    private class MetricsConfig {
        private final File configFile;
        private FileConfiguration config;

        private MetricsConfig() {
            File pluginFolder = plugin.getDataFolder().getParentFile();
            File metricsFolder = new File(pluginFolder, FOLDER_NAME);
            configFile = new File(metricsFolder, CONFIGFILE_NAME);
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

    private class NJson {
        private StringBuilder builder;

        private NJson() {
            builder = new StringBuilder();
        }

        private NJson put(String key, String value) {
            StringBuilder b = new StringBuilder();
            if (builder.length() > 0) {
                b.append(",");
            }
            b.append("{\"" + key + "\":\"" + value + "\"}");
            builder.append(b.toString());
            return this;
        }

        private NJson putArray(String key, String... values) {
            return putArray(key, Arrays.asList(values));
        }

        private NJson putArray(String key, List<String> values) {
            StringBuilder b = new StringBuilder();
            if (builder.length() > 0) {
                b.append(",");
            }
            b.append("{\"" + key + "\":" + array(values));
            builder.append(b.toString());
            return this;
        }

        private String array(String... elements) {
            return array(Arrays.asList(elements));
        }

        private String array(List<String> elements) {
            StringBuilder b = new StringBuilder();
            for (String e : elements) {
                if (b.length() > 0) {
                    b.append(",");
                }
                b.append(e);
            }
            return "[" + b.toString() + "]";
        }

        public String toString() {
            return "{" + builder.toString() + "}";
        }
    }
}
