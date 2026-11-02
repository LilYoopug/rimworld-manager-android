package com.lilyoopug.rimanager;

import android.os.ParcelFileDescriptor;
import com.lilyoopug.rimanager.IProgressListener;

interface IModService {
    void destroy() = 16777114;

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
    // Imports one zip: every mod folder in it is copied into the Mods folder. Progress goes to listener.
    String importZip(String jobId, String fileName, long totalBytes, in ParcelFileDescriptor pfd, IProgressListener listener) = 8;

    // Files (paths start with "files/")
    // Streams About/Preview.png (preview=true) or About/ModIcon.png. Null when the mod has no such image.
    ParcelFileDescriptor openModImage(String folderName, boolean preview) = 9;

    String listDir(String relPath) = 10;                   // name|isDir(1/0)
    String readText(String relPath) = 11;
    void writeText(String relPath, String content, boolean backup) = 12;
    String importToGame(String gamePath, in ParcelFileDescriptor pfd) = 13;
    String exportFile(String gamePath) = 14;               // copies into RimMods/exports
    // Exports several files and folders into RimMods/exports. Folders become .tar files.
    String exportPaths(in String[] gamePaths) = 15;
    String copyInGame(String srcPath, String dstPath) = 16;
    String moveInGame(String srcPath, String dstPath) = 17;
    String deleteInGame(in String[] paths) = 18;
    String makeDirInGame(String path) = 19;

    // Saves. listSaves returns JSON: [{name,ticks,play,ver,scenario,mods}]
    String listSaves() = 20;
    String renameSave(String oldName, String newName) = 21;
    String duplicateSave(String name, String newName) = 22;
    String deleteSaves(in String[] names) = 23;
    String exportSaves(in String[] names) = 24;
}
