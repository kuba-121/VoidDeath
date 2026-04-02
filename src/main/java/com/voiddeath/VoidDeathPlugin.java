package com.voiddeath;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VoidDeathPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<String, Long> bannedPlayers = new ConcurrentHashMap<>();
    private File dataFile;
    private FileConfiguration dataConfig;
    
    private final boolean isFolia = isClassPresent("io.papermc.paper.threadedregions.RegionScheduler");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        
        getServer().getPluginManager().registerEvents(this, this);

        getCommand("revive").setExecutor(this);
        getCommand("cleardeadlist").setExecutor(this);
        getCommand("deadlist").setExecutor(this);
        getCommand("voiddeath").setExecutor(this);
        
        getLogger().info("VoidDeath enabled! Engine: " + (isFolia ? "Folia" : "Standard (Spigot/Paper)"));
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        bannedPlayers.clear();
        if (dataConfig.contains("banned")) {
            for (String uuid : dataConfig.getConfigurationSection("banned").getKeys(false)) {
                bannedPlayers.put(uuid, dataConfig.getLong("banned." + uuid));
            }
        }
    }

    private void saveData() {
        Runnable saveTask = () -> {
            dataConfig.set("banned", null);
            for (Map.Entry<String, Long> entry : bannedPlayers.entrySet()) {
                dataConfig.set("banned." + entry.getKey(), entry.getValue());
            }
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                getLogger().severe("Could not save data.yml!");
            }
        };

        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().execute(this, saveTask);
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(this, saveTask);
        }
    }

    private String msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        String message = getConfig().getString("messages." + path);
        
        if (message == null) return ChatColor.RED + "Missing config path: " + path;

        String formatted = message.replace("%prefix%", prefix);
        return ChatColor.translateAlternateColorCodes('&', formatted);
    }

    private String getKickReason(long expiry) {
        String prefix = getConfig().getString("messages.prefix", "");
        List<String> lines = getConfig().getStringList("messages.kick-message");
        String timeLeft = formatTimeLeft(expiry);
        
        return lines.stream()
                .map(line -> {
                    String formatted = line.replace("%prefix%", prefix).replace("%time%", timeLeft);
                    return ChatColor.translateAlternateColorCodes('&', formatted);
                })
                .collect(Collectors.joining("\n"));
    }

    private long parseDuration(String duration) {
        if (duration.equalsIgnoreCase("-1") || duration.equalsIgnoreCase("perm")) return -1;
        try {
            long time = Long.parseLong(duration.substring(0, duration.length() - 1));
            char unit = duration.toLowerCase().charAt(duration.length() - 1);
            switch (unit) {
                case 's': return System.currentTimeMillis() + (time * 1000);
                case 'm': return System.currentTimeMillis() + (time * 60000);
                case 'h': return System.currentTimeMillis() + (time * 3600000);
                case 'd': return System.currentTimeMillis() + (time * 86400000);
                default: return -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private String formatTimeLeft(long expiry) {
        if (expiry == -1) return msg("time-perm");
        long left = (expiry - System.currentTimeMillis()) / 1000;
        if (left <= 0) return "0s";
        
        long days = left / 86400;
        long hours = (left % 86400) / 3600;
        long minutes = (left % 3600) / 60;
        long seconds = left % 60;
        
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0) sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        
        if (getConfig().getBoolean("admin-bypass") && p.hasPermission("voiddeath.bypass")) {
            return;
        }

        long expiry = parseDuration(getConfig().getString("ban-duration", "perm"));
        bannedPlayers.put(p.getUniqueId().toString(), expiry);
        saveData();

        String broadcastMsg = msg("elimination-broadcast").replace("%player%", p.getName());
        String kickReason = getKickReason(expiry);

        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().execute(this, () -> Bukkit.broadcastMessage(broadcastMsg));
            p.getScheduler().run(this, task -> p.kickPlayer(kickReason), null);
        } else {
            Bukkit.getScheduler().runTask(this, () -> {
                Bukkit.broadcastMessage(broadcastMsg);
                p.kickPlayer(kickReason);
            });
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String uuid = e.getUniqueId().toString();
        if (bannedPlayers.containsKey(uuid)) {
            long expiry = bannedPlayers.get(uuid);
            
            if (expiry != -1 && expiry < System.currentTimeMillis()) {
                bannedPlayers.remove(uuid);
                saveData();
                return;
            }
            
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, getKickReason(expiry));
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("voiddeath")) {
            if (args.length > 0) {
                if (args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("voiddeath.admin")) {
                        sender.sendMessage(msg("no-permission"));
                        return true;
                    }
                    reloadConfig();
                    loadData();
                    sender.sendMessage(msg("reload-success"));
                    return true;
                }
                
                if (args[0].equalsIgnoreCase("help")) {
                    List<String> helpMenu = getConfig().getStringList("messages.help-menu");
                    for (String line : helpMenu) {
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
                    }
                    return true;
                }
                
                sender.sendMessage(msg("usage-voiddeath"));
                return true;
            }
            
            List<String> helpMenu = getConfig().getStringList("messages.help-menu");
            for (String line : helpMenu) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("revive")) {
            if (!sender.hasPermission("voiddeath.revive")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(msg("usage-revive"));
                return true;
            }
            
            String targetName = args[0];
            @SuppressWarnings("deprecation")
            OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
            String uuid = op.getUniqueId().toString();

            if (bannedPlayers.remove(uuid) != null) {
                saveData();
                sender.sendMessage(msg("revive-success").replace("%player%", targetName));
            } else {
                sender.sendMessage(msg("revive-not-found").replace("%player%", targetName));
            }
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("cleardeadlist")) {
            if (!sender.hasPermission("voiddeath.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            bannedPlayers.clear();
            saveData();
            sender.sendMessage(msg("clear-success"));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("deadlist")) {
            if (!sender.hasPermission("voiddeath.admin")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if (bannedPlayers.isEmpty()) {
                sender.sendMessage(msg("list-empty"));
            } else {
                sender.sendMessage(msg("list-header"));
                bannedPlayers.forEach((uuidStr, expiry) -> {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                    String name = op.getName();
                    sender.sendMessage(msg("list-item")
                        .replace("%player%", (name != null ? name : uuidStr))
                        .replace("%time%", formatTimeLeft(expiry)));
                });
            }
            return true;
        }
        return true;
    }
}