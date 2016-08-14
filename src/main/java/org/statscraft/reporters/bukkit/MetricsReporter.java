package org.statscraft.reporters.bukkit;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class MetricsReporter {
    // Constants
    private static final int API_VERSION = 1;
    private static final String API_BASE_URL = "http://api.statscraft.org/v" + API_VERSION + "/report";
    private static final String API_PLUGIN_URL = API_BASE_URL + "/plugin";
    private static final String API_SERVER_URL = API_BASE_URL + "/server";
    private static final String API_UPDATE_URL = API_BASE_URL + "/update";

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

    public MetricsReporter(Plugin plugin, String authKey) {
        // Get instances
        this.plugin = plugin;
        this.server = this.plugin.getServer();
        this.scheduler = this.server.getScheduler();
        // Key
        this.authKey = authKey;
        // Reset status
        started = false;
    }

    public boolean start() {
        // Stop if the metrics service is already running
        if(started) {
            return true;
        }

        // Ignore if the plugin is disabled
        if(!plugin.isEnabled()) {
            return false;
        }

        // Load config
        config = new MetricsConfig().load();

        // Stop if metrics are disabled in the config
        if (config.isOptOut()) {
            return false;
        }

        // Send plugin data async
        scheduler.runTaskAsynchronously(plugin, new PluginReportTask());

        // Schedule post server boot task
        scheduler.scheduleSyncDelayedTask(plugin, new Runnable() {
            @Override
            public void run() {
                // If the plugin was disabled do nothing
                if(!plugin.isEnabled()) {
                    return;
                }

                // Schedule the server data sender
                scheduler.runTaskAsynchronously(plugin, new ServerReportTask());

                // Schedule server ping task
                scheduler.runTaskTimerAsynchronously(plugin, new UpdateTask(), 0, UPDATE_DELAY);
            }
        });

        // Everything ok
        started = true;
        return true;
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
        if(response == null) {
            throw new IOException("Response was null");
        }
        if(response.startsWith("ERR")) {
            throw new IOException(response);
        }
        if(response.startsWith("7")) {
            if(response.startsWith("7,")) {
                throw new IOException(response.substring(2));
            }
            throw new IOException(response.substring(1));
        }
    }

    private class PluginReportTask implements Runnable {
        @Override
        public void run() {
            if(!plugin.isEnabled()) {
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
            if(!plugin.isEnabled()) {
                return;
            }

            // Os data
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");
            // System data TODO: i'm not sure this works with non-oracle JVM
            com.sun.management.OperatingSystemMXBean systemMXBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            int coreCount = systemMXBean.getAvailableProcessors();
            long availableRam = systemMXBean.getTotalPhysicalMemorySize();
            // Jvm data
            String javaArch = System.getProperty("os.version");
            String javaVersion = System.getProperty("java.version");
            // Server instance data
            String serverVersion = server.getBukkitVersion();
            String minecraftVersion = server.getVersion();

            NJson json = new NJson()
                    .put("type", "server")
                    .put("authKey", authKey);

            // Send the data
            try {
                sendJson(API_SERVER_URL, json.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class UpdateTask implements Runnable {
        @Override
        public void run() {
            //TODO: everything :P
        }
    }

    private class MetricsConfig {
        private final File configFile;
        private FileConfiguration config;

        public MetricsConfig() {
            File pluginFolder = plugin.getDataFolder().getParentFile();
            File metricsFolder = new File(pluginFolder, FOLDER_NAME);
            configFile = new File(metricsFolder, CONFIGFILE_NAME);
        }

        public MetricsConfig load() {
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
            if(changes) {
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

        public String getUuid() {
            return config.getString("uuid");
        }

        public boolean isOptOut() {
            return config.getBoolean("opt-out");
        }
    }

    private class NJson {
        private StringBuilder builder;

        public NJson() {
            builder = new StringBuilder();
        }

        public String prepare(String string) {
            return string.replace("\"", "\"\"");
        }

        public NJson put(String key, String value) {
            StringBuilder b = new StringBuilder();
            if(builder.length() > 0) {
                b.append(",");
            }
            b.append("{\"" + key + "\":\"" + value + "\"}");
            builder.append(b.toString());
            return this;
        }

        public NJson putArray(String key, String... values) {
            return putArray(key, Arrays.asList(values));
        }

        public NJson putArray(String key, List<String> values) {
            StringBuilder b = new StringBuilder();
            if(builder.length() > 0) {
                b.append(",");
            }
            b.append("{\"" + key + "\":" + array(values));
            builder.append(b.toString());
            return this;
        }

        public String array(String... elements) {
            return array(Arrays.asList(elements));
        }

        public String array(List<String> elements) {
            StringBuilder b = new StringBuilder();
            for(String e : elements) {
                if(b.length() > 0) {
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
