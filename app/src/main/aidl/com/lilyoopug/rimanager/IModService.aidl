package com.lilyoopug.rimanager;

import android.os.ParcelFileDescriptor;

interface IModService {
    // Mods. Returns JSON:
    // {"active":["packageid",...], "mods":[{folder,name,pid,author,modVer,supported[],deps[{pid,name}],after[],desc}]}
    String listMods() = 1;
    // Writes the load order (packageIds, in order). ludeon.* entries are always kept.
    String setActiveMods(in String[] packageIds, boolean backup) = 2;
    String deleteMods(in String[] folderNames) = 3;
    String importMods(in String[] folderNames) = 4;       // copies staging folders into the Mods folder
    String exportMod(String folderName) = 5;              // tar into RimMods/exports
    String listStaging() = 6;
    String writeStaging(String relPath, in ParcelFileDescriptor pfd) = 7; // streams a file into staging
    // Imports one zip: every mod folder in it is copied into the Mods folder. One result line per mod.
    String importZip(String fileName, in ParcelFileDescriptor pfd) = 8;

    // Files (paths start with "files/")
    String listDir(String relPath) = 9;                   // name|isDir(1/0)
    String readText(String relPath) = 10;
    void writeText(String relPath, String content, boolean backup) = 11;
    String importToGame(String gamePath, in ParcelFileDescriptor pfd) = 12;
    String exportFile(String gamePath) = 13;              // copies into RimMods/exports
    // Exports several files and folders into RimMods/exports. Folders become .tar files.
    String exportPaths(in String[] gamePaths) = 14;
    String copyInGame(String srcPath, String dstPath) = 15;
    String moveInGame(String srcPath, String dstPath) = 16;
    String deleteInGame(in String[] paths) = 17;
    String makeDirInGame(String path) = 18;

    // Saves. listSaves returns JSON: [{name,ticks,play,ver,scenario,mods}]
    String listSaves() = 19;
    String renameSave(String oldName, String newName) = 20;
    String duplicateSave(String name, String newName) = 21;
    String deleteSaves(in String[] names) = 22;
    String exportSaves(in String[] names) = 23;

    void destroy() = 16777114;
}
