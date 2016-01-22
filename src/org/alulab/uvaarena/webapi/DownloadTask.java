/*
 * Copyright (c) 2016, Sudipto Chandra
 * 
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.alulab.uvaarena.webapi;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import org.alulab.uvaarena.util.Commons;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Abstract class to handle downloading task.
 */
public abstract class DownloadTask {

    final int WAITING = 0;
    final int RUNNING = 1;
    final int STOPPING = 2;
    final int FINISHED = 3;
    final int BUFFER_SIZE = 2048;
    final int REPORT_INTERVAL_MILLIS = 100;

    private volatile int mStatus = WAITING;
    private int mRetryCount = 0;
    private long mTotalBytes = 0;
    private long mDownloadedBytes = 0;
    private Exception mError = null;
    private long mLastReportTime = 0;
    private String mEncoding = null;
    private boolean mChunked = false;

    private final String mUrl;
    private final DownloadThread mThread;
    private final CloseableHttpClient mClient;
    private final ArrayList<TaskMonitor> mTaskMonitors;

    /**
     * Initializes an instance of this class
     *
     * @param client
     * @param url
     */
    public DownloadTask(CloseableHttpClient client, String url) {
        mUrl = url;
        mClient = client;
        mTaskMonitors = new ArrayList<>();
        mThread = new DownloadThread();
    }

    abstract HttpUriRequest getUriRequest();

    abstract void beforeDownloadStart() throws IOException;

    abstract void processByte(byte[] data) throws IOException;

    abstract void afterDownloadSucceed() throws IOException;

    /**
     * Handles the task to download data
     */
    private final class DownloadThread extends Thread {

        public DownloadThread() {
            this.setPriority(Thread.MIN_PRIORITY);
        }

        @Override
        public void run() {
            mStatus = RUNNING;
            for (int i = 0; mStatus == RUNNING; ++i) {
                // reset some data
                mError = null;
                mTotalBytes = 0;
                mDownloadedBytes = 0;
                reportProgress();
                // get response       
                HttpUriRequest uriRequest = getUriRequest();
                try (CloseableHttpResponse response = mClient.execute(uriRequest)) {
                    // process response
                    beforeDownloadStart();
                    processResponse(response);
                    afterDownloadSucceed();
                    // report finish on success
                    mStatus = FINISHED;
                    reportFinish();
                    return;
                } catch (Exception ex) {
                    // stop download when retry slot is empty
                    if (i == getRetryCount() || mStatus != RUNNING) {
                        mError = ex;
                        // report finish on failure
                        mStatus = FINISHED;
                        reportFinish();
                        return;
                    }
                    // increase priority of this thread on failure
                    if (this.getPriority() < Thread.MAX_PRIORITY) {
                        this.setPriority(this.getPriority() + 1);
                    }
                }
            }
            // report finish on end of loop
            mError = new Exception("Download Failed");
            mStatus = FINISHED;
            reportFinish();
        }

        void processResponse(CloseableHttpResponse response) throws IOException, InterruptedException {
            // get entity
            HttpEntity entity = response.getEntity();
            mChunked = entity.isChunked();
            // get total bytes 
            mTotalBytes = Math.max(0, entity.getContentLength());
            reportProgress();
            // get content  
            byte[] data = new byte[BUFFER_SIZE];
            try (InputStream is = entity.getContent()) {
                for (int b; (b = is.read(data)) > 0;) {
                    processByte(data);
                    addDownloadedBytes(b);
                    reportProgress();
                    // check if download should continue
                    if (mStatus != RUNNING) {
                        throw new InterruptedException("Download was interrupted before it was finished.");
                    }
                }
            }
            // get encoding
            if (entity.getContentEncoding() != null) {
                mEncoding = entity.getContentEncoding().getValue();
            }
        }

    }

    /**
     * Starts the download. If download is running it ignores the request.
     */
    public void startDownload() {
        if (!mThread.isAlive()) {
            mThread.start();
        }
    }

    /**
     * Stops the download. If download is not ongoing it ignores the request.
     */
    public void stopDownload() {
        if (mThread.isAlive()) {
            mStatus = STOPPING;
        }
    }

    /**
     * Reports the current progress to all observers.
     */
    private void reportProgress() {
        long curtime = System.currentTimeMillis();
        if (curtime - mLastReportTime >= REPORT_INTERVAL_MILLIS) {
            mLastReportTime = curtime;
            mTaskMonitors.forEach((TaskMonitor runnable) -> {
                runnable.statusChanged(this);
            });
        }
    }

    /**
     * Reports that the download is finished to all observers.
     */
    private void reportFinish() {
        if (isFinished()) {
            mTaskMonitors.forEach((TaskMonitor runnable) -> {
                runnable.downloadFinished(this);
            });
        }
    }

    /**
     * Sets the task monitor to monitor progress of the download
     *
     * @param taskMonitor
     */
    public void addTaskMonitor(TaskMonitor taskMonitor) {
        if (taskMonitor != null) {
            mTaskMonitors.add(taskMonitor);
        }
    }

    /**
     * Gets the thread controlling the download
     *
     * @return
     */
    public Thread getDownloadThread() {
        return mThread;
    }

    /**
     * Gets the URL of the download task
     *
     * @return
     */
    public String getUrl() {
        return mUrl;
    }

    /**
     * Gets the total bytes to be downloaded.
     *
     * @return
     */
    public long getTotalBytes() {
        return mTotalBytes;
    }

    /**
     * Gets the length of total byte in well formatted string.
     *
     * @return
     */
    public String getTotalByteLength() {
        return Commons.formatByteLength(mTotalBytes);
    }

    /**
     * Set the total bytes to be downloaded.
     *
     * @param bytes
     */
    protected void setTotalBytes(long bytes) {
        mTotalBytes = bytes;
    }

    /**
     * Gets the number of bytes downloaded.
     *
     * @return
     */
    public long getDownloadedBytes() {
        return mDownloadedBytes;
    }

    /**
     * Sets the number of bytes downloaded.
     *
     * @param bytes
     */
    protected void setDownloadedBytes(long bytes) {
        mDownloadedBytes = bytes;
        mTotalBytes = Math.max(bytes, mTotalBytes);
    }

    /**
     * Adds the number of bytes with current downloaded bytes.
     *
     * @param bytesToAdd
     */
    protected void addDownloadedBytes(long bytesToAdd) {
        setDownloadedBytes(mDownloadedBytes + bytesToAdd);
    }

    /**
     * Gets the length of downloaded bytes in a well formatted string.
     *
     * @return
     */
    public String getDownloadedByteLength() {
        return Commons.formatByteLength(mDownloadedBytes);
    }

    /**
     * Gets the current progress of download in percentage.
     *
     * @return
     */
    public double getDownloadProgress() {
        if (mTotalBytes == 0) {
            return 1E-14;
        } else {
            return (double) mDownloadedBytes * 100.0 / (double) mTotalBytes + 1E-14;
        }
    }

    /**
     * Gets the current progress of download valid upto a fixed precision.
     *
     * @param precision Precision of the download progress.
     * @return
     */
    public String getDownloadProgress(int precision) {
        String args = "%f";
        if (precision > 0) {
            args = "%." + String.valueOf(precision) + "f";
        }
        return String.format(args, getDownloadProgress());
    }

    /**
     * Get the number of times to retry on failure.
     *
     * @return
     */
    public int getRetryCount() {
        return mRetryCount;
    }

    /**
     * Sets the number of times to retry on failure.
     *
     * @param count
     */
    public void setRetryCount(int count) {
        mRetryCount = Math.max(0, count);
    }

    /**
     * Gets the error message. If no error, "No Error" is returned.
     *
     * @return
     */
    public String getErrorMessage() {
        return (mError != null) ? mError.getMessage() : "No Error";
    }

    /**
     * Gets the Error. If none, a null value is returned.
     *
     * @return
     */
    public Exception getError() {
        return mError;
    }

    /**
     * Gets the content encoding. If encoding is unknown a NULL value is
     * returned indicating the default encoding should be used.
     *
     * @return
     */
    public String getEncoding() {
        return mEncoding;
    }

    /**
     * True if the last attempt to download received InputStream in chunk.
     *
     * @return
     */
    public boolean isChunked() {
        return mChunked;
    }

    /**
     * Gets the current status code of the task.
     *
     * @return
     */
    public int getStatusCode() {
        return mStatus;
    }

    /**
     * Gets the status message of the current task.
     *
     * @return
     */
    public String getStatusMessage() {
        switch (mStatus) {
            case WAITING:
                return "Waiting";
            case RUNNING:
                return "Running";
            case STOPPING:
                return "Stopping";
            case FINISHED:
                if (mError == null) {
                    return "Success";
                } else {
                    return "Failed : " + mError.getMessage();
                }
            default:
                return "Unknown";
        }
    }

    /**
     * True if the task is stopped and waiting to be called.
     *
     * @return
     */
    public boolean isWaiting() {
        return mStatus == WAITING;
    }

    /**
     * True if the task is running.
     *
     * @return
     */
    public boolean isRunning() {
        return mStatus == RUNNING || mStatus == STOPPING;
    }

    /**
     * True if the task has finished.
     *
     * @return
     */
    public boolean isFinished() {
        return mStatus == FINISHED;
    }

    /**
     * True if the task has finished successfully.
     *
     * @return
     */
    public boolean isSuccess() {
        return mStatus == FINISHED && mError == null;
    }

    /**
     * True if the task has failed to download properly.
     *
     * @return
     */
    public boolean isFailed() {
        return mStatus == FINISHED && mError != null;
    }

    @Override
    public String toString() {
        return String.format("%s : %s%% [%s of %s] ~ %s",
                mUrl, getDownloadProgress(2), getDownloadedByteLength(), getTotalByteLength(), getStatusMessage());
    }

}
