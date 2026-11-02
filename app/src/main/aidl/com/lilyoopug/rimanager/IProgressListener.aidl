package com.lilyoopug.rimanager;

// Called by the Shizuku service while a zip is imported. oneway: the service never waits for the app.
oneway interface IProgressListener {
    void onProgress(String jobId, int percent, String status) = 1;
}
