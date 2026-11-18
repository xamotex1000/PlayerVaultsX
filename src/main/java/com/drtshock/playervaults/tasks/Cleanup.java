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

package com.drtshock.playervaults.tasks;

import com.drtshock.playervaults.PlayerVaults;
import com.drtshock.playervaults.vaultmanagement.VaultManager;

import java.io.File;

public class Cleanup implements Runnable {

    private final long diff;

    public Cleanup(long diff) {
        this.diff = diff * 86400000L;
    }

    @Override
    public void run() {
        File directory = PlayerVaults.getInstance().getVaultData();
        if (!directory.exists()) {
            // folder doesn't exist, don't run
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - diff;
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            // Check-and-delete under the vault file's lock, so a vault saved while this runs isn't purged.
            if (VaultManager.getInstance().deleteVaultFileIfOlderThan(file, cutoff)) {
                PlayerVaults.getInstance().getLogger().info("Deleted vault file (cleanup): " + file.getName());
            }
        }
    }
}
