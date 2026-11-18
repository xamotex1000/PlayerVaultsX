/*
 * PlayerVaultsX
 * Copyright (C) 2013 Trent Hensler
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.drtshock.playervaults.vaultmanagement;

import com.drtshock.playervaults.PlayerVaults;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import com.drtshock.playervaults.util.Scheduler;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class VaultManager {

    private static final String VAULTKEY = "vault%d";
    private static final int LOCK_STRIPES = 64;
    private static VaultManager instance;
    private final File directory = PlayerVaults.getInstance().getVaultData();
    /*
     * Cached vault files. Entries in this map are treated as immutable once published: every
     * modification is made on a copy which then replaces the cached entry. That lets threads read
     * a cached file without holding a lock while another thread (another Folia region, or an async
     * task) is saving to the same holder.
     */
    private final Map<String, YamlConfiguration> cachedVaultFiles = new ConcurrentHashMap<>();
    /*
     * Striped locks guarding all disk access (and cache updates) for a holder's file. Without them,
     * two threads saving different vaults of the same holder can each load the file, modify their
     * own vault and write back, silently discarding the other's change; or a reader can load a
     * half-written file.
     */
    private final Object[] fileLocks = new Object[LOCK_STRIPES];
    private final PlayerVaults plugin;

    public VaultManager(PlayerVaults plugin) {
        this.plugin = plugin;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.fileLocks[i] = new Object();
        }
        instance = this;
    }

    /**
     * Get the instance of this class.
     *
     * @return - instance of this class.
     */
    public static VaultManager getInstance() {
        return instance;
    }

    private Object lockFor(String holder) {
        return this.fileLocks[(holder.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    /**
     * Saves the inventory to the specified player and vault number.
     *
     * @param inventory The inventory to be saved.
     * @param target The player of whose file to save to.
     * @param number The vault number.
     */
    public void saveVault(Inventory inventory, String target, int number) {
        // Serialize on the calling thread (which owns the inventory), outside the file lock.
        String serialized = CardboardBoxSerialization.toStorage(inventory, target);
        synchronized (lockFor(target)) {
            YamlConfiguration yaml = copyOf(getPlayerVaultFile(target, true));
            yaml.set(String.format(VAULTKEY, number), serialized);
            saveFileSync(target, yaml);
        }
    }

    /**
     * Load the player's vault and return it.
     *
     * @param player The holder of the vault.
     * @param number The vault number.
     */
    public Inventory loadOwnVault(Player player, int number, int size) {
        if (size % 9 != 0) {
            size = PlayerVaults.getInstance().getDefaultVaultSize();
        }

        PlayerVaults.debug("Loading self vault for " + player.getName() + " (" + player.getUniqueId() + ')');

        String title = PlayerVaults.getInstance().getVaultTitle(String.valueOf(number));
        VaultViewInfo info = new VaultViewInfo(player.getUniqueId().toString(), number);
        Inventory open = PlayerVaults.getInstance().getOpenInventories().get(info.toString());
        if (open != null) {
            PlayerVaults.debug("Already open");
            return open;
        }

        YamlConfiguration playerFile = getPlayerVaultFile(player.getUniqueId().toString(), true);
        VaultHolder vaultHolder = new VaultHolder(number);
        Inventory inv;
        if (playerFile.getString(String.format(VAULTKEY, number)) == null) {
            PlayerVaults.debug("No vault matching number");
            inv = Bukkit.createInventory(vaultHolder, size, title);
        } else {
            inv = getInventory(vaultHolder, player.getUniqueId().toString(), playerFile, size, number, title);
        }
        vaultHolder.setInventory(inv);
        return publishOpenInventory(info, inv);
    }

    /**
     * Load the player's vault and return it.
     *
     * @param name The holder of the vault.
     * @param number The vault number.
     */
    public Inventory loadOtherVault(String name, int number, int size) {
        if (size % 9 != 0) {
            size = PlayerVaults.getInstance().getDefaultVaultSize();
        }

        PlayerVaults.debug("Loading other vault for " + name);

        String holder = name;

        try {
            UUID uuid = UUID.fromString(name);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            holder = offlinePlayer.getUniqueId().toString();
        } catch (Exception e) {
            // Not a player
        }

        String title = PlayerVaults.getInstance().getVaultTitle(String.valueOf(number));
        VaultViewInfo info = new VaultViewInfo(name, number);
        Inventory open = PlayerVaults.getInstance().getOpenInventories().get(info.toString());
        if (open != null) {
            PlayerVaults.debug("Already open");
            return open;
        }
        YamlConfiguration playerFile = getPlayerVaultFile(holder, true);
        VaultHolder vaultHolder = new VaultHolder(number);
        Inventory inv = getInventory(vaultHolder, holder, playerFile, size, number, title);
        if (inv == null) {
            return null;
        }
        vaultHolder.setInventory(inv);
        return publishOpenInventory(info, inv);
    }

    /**
     * Registers a freshly loaded vault inventory as the shared open copy, unless another thread beat
     * us to it, in which case that thread's copy wins and is returned. This guarantees everyone
     * viewing a given vault sees the same Inventory object (otherwise two concurrent openers on
     * different Folia regions would each get their own copy and the last one closed would overwrite
     * the other's changes).
     */
    private Inventory publishOpenInventory(VaultViewInfo info, Inventory inv) {
        Inventory existing = PlayerVaults.getInstance().getOpenInventories().putIfAbsent(info.toString(), inv);
        if (existing != null) {
            PlayerVaults.debug("Already open (concurrently)");
            return existing;
        }
        return inv;
    }

    /**
     * Forget a shared open vault inventory if nobody ended up viewing it (e.g. the open was cancelled).
     *
     * @param info the vault
     * @param inventory the inventory that was registered for it
     */
    void discardIfUnviewed(VaultViewInfo info, Inventory inventory) {
        if (inventory.getViewers().isEmpty()) {
            PlayerVaults.getInstance().getOpenInventories().remove(info.toString(), inventory);
        }
    }

    /**
     * Get an inventory from file. Returns null if the inventory doesn't exist. SHOULD ONLY BE USED INTERNALLY
     *
     * @param playerFile the YamlConfiguration file.
     * @param size the size of the vault.
     * @param number the vault number.
     * @return inventory if exists, otherwise null.
     */
    private Inventory getInventory(InventoryHolder owner, String ownerName, YamlConfiguration playerFile, int size, int number, String title) {
        Inventory inventory = Bukkit.createInventory(owner, size, title);

        String data = playerFile.getString(String.format(VAULTKEY, number));
        ItemStack[] deserialized = CardboardBoxSerialization.fromStorage(data, ownerName);
        if (deserialized == null) {
            PlayerVaults.debug("Loaded vault for " + ownerName + " as null");
            return inventory;
        }

        // Check if deserialized has more used slots than the limit here.
        // Happens on change of permission or if people used the broken version.
        // In this case, players will lose items.
        if (deserialized.length > size) {
            PlayerVaults.debug("Loaded vault for " + ownerName + " and got " + deserialized.length + " items for allowed size of " + size+". Attempting to rescue!");
            for (ItemStack stack : deserialized) {
                if (stack != null) {
                    inventory.addItem(stack);
                }
            }
        } else {
            inventory.setContents(deserialized);
        }

        PlayerVaults.debug("Loaded vault");
        return inventory;
    }

    /**
     * Gets an inventory without storing references to it. Used for dropping a players inventories on death.
     *
     * @param holder The holder of the vault.
     * @param number The vault number.
     * @return The inventory of the specified holder and vault number. Can be null.
     */
    public Inventory getVault(String holder, int number) {
        YamlConfiguration playerFile = getPlayerVaultFile(holder, true);
        String serialized = playerFile.getString(String.format(VAULTKEY, number));
        ItemStack[] contents = CardboardBoxSerialization.fromStorage(serialized, holder);
        Inventory inventory = Bukkit.createInventory(null, contents.length, holder + " vault " + number);
        inventory.setContents(contents);
        return inventory;
    }

    /**
     * Checks if a vault exists.
     *
     * @param holder holder of the vault.
     * @param number vault number.
     * @return true if the vault file and vault number exist in that file, otherwise false.
     */
    public boolean vaultExists(String holder, int number) {
        File file = new File(directory, holder + ".yml");
        if (!file.exists()) {
            return false;
        }

        return getPlayerVaultFile(holder, true).contains(String.format(VAULTKEY, number));
    }

    /**
     * Gets the numbers belonging to all their vaults.
     *
     * @param holder holder
     * @return a set of Integers, which are player's vaults' numbers (fuck grammar).
     */
    public Set<Integer> getVaultNumbers(String holder) {
        Set<Integer> vaults = new HashSet<>();
        YamlConfiguration file = getPlayerVaultFile(holder, true);
        if (file == null) {
            return vaults;
        }

        for (String s : file.getKeys(false)) {
            try {
                // vault%
                int number = Integer.parseInt(s.substring(4));
                vaults.add(number);
            } catch (NumberFormatException e) {
                // silent
            }
        }


        return vaults;
    }

    public void deleteAllVaults(String holder) {
        synchronized (lockFor(holder)) {
            removeCachedPlayerVaultFile(holder);
            deletePlayerVaultFile(holder);
        }
    }

    /**
     * Deletes a players vault.
     *
     * @param sender The sender of whom to send messages to.
     * @param holder The vault holder.
     * @param number The vault number.
     */
    public void deleteVault(CommandSender sender, final String holder, final int number) {
        final String key = String.format(VAULTKEY, number);

        // Update the in-memory copy right away, so a reopen before the disk write finishes doesn't
        // resurrect the deleted contents.
        synchronized (lockFor(holder)) {
            YamlConfiguration cached = cachedVaultFiles.get(holder);
            if (cached != null && cached.contains(key)) {
                YamlConfiguration updated = copyOf(cached);
                updated.set(key, null);
                cachedVaultFiles.put(holder, updated);
            }
        }

        Scheduler.runAsync(() -> {
            synchronized (lockFor(holder)) {
                File file = new File(directory, holder + ".yml");
                if (!file.exists()) {
                    return;
                }

                // Re-read the current state while holding the lock, so a save that happened after
                // this task was scheduled is not overwritten with stale data.
                YamlConfiguration current = getPlayerVaultFile(holder, false);
                if (current == null) {
                    return;
                }
                YamlConfiguration playerFile = copyOf(current);
                playerFile.set(key, null);
                cachedVaultFiles.replace(holder, playerFile);
                try {
                    playerFile.save(file);
                } catch (IOException ignored) {
                }
            }
        });

        OfflinePlayer player = Bukkit.getPlayer(holder);
        if (player != null) {
            if (sender.getName().equalsIgnoreCase(player.getName())) {
                this.plugin.getTL().deleteVault().title().with("vault", String.valueOf(number)).send(sender);
            } else {
                this.plugin.getTL().deleteOtherVault().title().with("vault", String.valueOf(number)).with("player", player.getName()).send(sender);
            }
        }

        // Drop the shared open copy for the vault that was deleted (the holder's, not the sender's).
        PlayerVaults.getInstance().getOpenInventories().remove(new VaultViewInfo(holder, number).toString());
    }

    // Should only be run asynchronously
    public void cachePlayerVaultFile(String holder) {
        synchronized (lockFor(holder)) {
            YamlConfiguration config = this.loadPlayerVaultFile(holder, false);
            if (config != null) {
                this.cachedVaultFiles.put(holder, config);
            }
        }
    }

    public void removeCachedPlayerVaultFile(String holder) {
        cachedVaultFiles.remove(holder);
    }

    /**
     * Get the holder's vault file. Create if doesn't exist.
     * <p>
     * The returned configuration may be shared with other threads; do not modify it. Copy it, modify
     * the copy and pass it to {@link #saveFileSync(String, YamlConfiguration)} instead.
     *
     * @param holder The vault holder.
     * @return The holder's vault config file.
     */
    public YamlConfiguration getPlayerVaultFile(String holder, boolean createIfNotFound) {
        YamlConfiguration cached = cachedVaultFiles.get(holder);
        if (cached != null) {
            return cached;
        }
        synchronized (lockFor(holder)) {
            cached = cachedVaultFiles.get(holder);
            if (cached != null) {
                return cached;
            }
            return loadPlayerVaultFile(holder, createIfNotFound);
        }
    }

    public YamlConfiguration loadPlayerVaultFile(String holder) {
        return this.loadPlayerVaultFile(holder, true);
    }

    /**
     * Attempt to delete a vault file.
     *
     * @param holder UUID of the holder.
     */
    public void deletePlayerVaultFile(String holder) {
        synchronized (lockFor(holder)) {
            File file = new File(this.directory, holder + ".yml");
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * Deletes a vault file if it hasn't been modified since the given time. The check and the
     * delete happen under the holder's lock so a vault saved in the meantime isn't purged.
     *
     * @param file the vault file
     * @param cutoff epoch millis; files last modified before this are deleted
     * @return true if the file was deleted
     */
    public boolean deleteVaultFileIfOlderThan(File file, long cutoff) {
        String name = file.getName();
        String holder = name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
        synchronized (lockFor(holder)) {
            if (!file.exists() || file.lastModified() >= cutoff) {
                return false;
            }
            removeCachedPlayerVaultFile(holder);
            return file.delete();
        }
    }

    public YamlConfiguration loadPlayerVaultFile(String uniqueId, boolean createIfNotFound) {
        synchronized (lockFor(uniqueId)) {
            if (!this.directory.exists()) {
                this.directory.mkdir();
            }

            File file = new File(this.directory, uniqueId + ".yml");
            if (!file.exists()) {
                if (createIfNotFound) {
                    try {
                        file.createNewFile();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    return null;
                }
            }

            return YamlConfiguration.loadConfiguration(file);
        }
    }

    public void saveFileSync(final String holder, final YamlConfiguration yaml) {
        synchronized (lockFor(holder)) {
            cachedVaultFiles.replace(holder, yaml);

            final boolean backups = PlayerVaults.getInstance().isBackupsEnabled();
            final File backupsFolder = PlayerVaults.getInstance().getBackupsFolder();
            final File file = new File(directory, holder + ".yml");
            if (file.exists() && backups) {
                file.renameTo(new File(backupsFolder, holder + ".yml"));
            }
            try {
                yaml.save(file);
            } catch (IOException e) {
                PlayerVaults.getInstance().addException(new IllegalStateException("Failed to save vault file for: " + holder, e));
                PlayerVaults.getInstance().getLogger().log(Level.SEVERE, "Failed to save vault file for: " + holder, e);
            }
        }
        PlayerVaults.debug("Saved vault for " + holder);
    }

    private static YamlConfiguration copyOf(YamlConfiguration source) {
        YamlConfiguration copy = new YamlConfiguration();
        if (source != null) {
            copyInto(source, copy);
        }
        return copy;
    }

    private static void copyInto(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            Object value = from.get(key);
            if (value instanceof ConfigurationSection section) {
                copyInto(section, to.createSection(key));
            } else {
                to.set(key, value);
            }
        }
    }
}
