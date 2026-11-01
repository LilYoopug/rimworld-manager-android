package com.lilyoopug.rimanager;

interface IModService {
    // Reserved destroy transaction used by Shizuku when the service is unbound.
    void destroy() = 16777114;

    // One line per mod: folder|name|packageId|enabled(1/0)
    String listMods();

    // Folder names inside /storage/emulated/0/RimMods (created if missing), one per line.
    String listStaging();

    // Read a text file relative to the game's private data folder.
    String readText(String relPath);

    // Write a text file relative to the game's private data folder. XML is validated first,
    // and the previous copy is saved as <file>.bak.
    void writeText(String relPath, String content);

    // Set activeMods in ModsConfig.xml. Base game and DLC entries are kept automatically.
    String setActiveMods(in String[] packageIds);

    // Remove mod folders from the game's Mods folder.
    String deleteMods(in String[] folderNames);

    // Copy staged mod folders into the game's Mods folder.
    String importMods(in String[] folderNames);
}
