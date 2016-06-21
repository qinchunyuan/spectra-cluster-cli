package uk.ac.ebi.pride.spectracluster.clustering;

import uk.ac.ebi.pride.spectracluster.util.ClusteringJobReference;
import uk.ac.ebi.pride.spectracluster.util.IProgressListener;
import uk.ac.ebi.pride.spectracluster.util.ProgressUpdate;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Created by jg on 30.05.15.
 */
public class BinaryFileClusterer {
    private final int nJobs;
    private final File outputDirectory;
    private final List<Float> thresholds;
    private final boolean fastMode;
    private final File temporaryDirectory;

    private ExecutorService clusteringExecuteService;
    private ClusteringProcessLauncher clusteringProcessLauncher;
    private List<IBinaryClusteringResultListener> listeners = new ArrayList<IBinaryClusteringResultListener>();
    private List<IProgressListener> progressListeners = new ArrayList<IProgressListener>();

    private List<BinaryClusterFileReference> resultFiles;

    public BinaryFileClusterer(int nJobs, File outputDirectory, List<Float> thresholds, boolean fastMode, File temporaryDirectory) {
        this.nJobs = nJobs;
        this.outputDirectory = outputDirectory;
        this.thresholds = thresholds;
        this.fastMode = fastMode;
        this.temporaryDirectory = temporaryDirectory;
    }

    public void clusterFiles(List<BinaryClusterFileReference> binaryFiles) throws Exception {
        launchClusteringJobs(binaryFiles);

        waitForCompletedJobs();
    }

    private void waitForCompletedJobs() throws Exception {
        List<Future<ClusteringJobReference>> fileFutures = clusteringProcessLauncher.getResultFileFutures();

        boolean allDone = false;
        resultFiles = new ArrayList<BinaryClusterFileReference>();
        Set<Integer> completedJobs = new HashSet<Integer>();

        while (!allDone) {
            allDone = true;

            for (int i = 0; i < fileFutures.size(); i++) {
                if (completedJobs.contains(i))
                    continue;

                Future<ClusteringJobReference> fileFuture = fileFutures.get(i);

                if (!fileFuture.isDone()) {
                    allDone = false;
                }
                else {
                    // save the written file
                    BinaryClusterFileReference resultFile = fileFuture.get().getOutputFile();
                    resultFiles.add(resultFile);
                    // notify all listeners
                    notifyListeners(resultFiles.size(), fileFutures.size(), resultFile);

                    completedJobs.add(i);
                }
            }

            Thread.sleep(1000); // only check every second
        }

        // terminate the executor service
        clusteringExecuteService.awaitTermination(2, TimeUnit.SECONDS);
    }

    private void launchClusteringJobs(List<BinaryClusterFileReference> binaryFiles) {
        clusteringExecuteService = Executors.newFixedThreadPool(nJobs);
        clusteringProcessLauncher = new ClusteringProcessLauncher(clusteringExecuteService, outputDirectory,
                thresholds, fastMode, temporaryDirectory);

        for (BinaryClusterFileReference binaryFile : binaryFiles) {
            clusteringProcessLauncher.onNewResultFile(binaryFile);
        }

        clusteringExecuteService.shutdown();
    }

    public void addListener(IBinaryClusteringResultListener listener) {
        listeners.add(listener);
    }

    public List<BinaryClusterFileReference> getResultFiles() {
        return Collections.unmodifiableList(resultFiles);
    }

    public void addProgressListener(IProgressListener progressListener) {
        progressListeners.add(progressListener);
    }

    private void notifyListeners(int completedJobs, int totalJobs, BinaryClusterFileReference writtenFile) {
        for (IBinaryClusteringResultListener listener : listeners) {
            listener.onNewResultFile(writtenFile);
        }

        ProgressUpdate progressUpdate = new ProgressUpdate(
                String.format("Completed clustering %d spectra (%f.2 m/z to %f.2 m/z)",
                        writtenFile.getnSpectra(), writtenFile.getMinMz(), writtenFile.getMaxMz()),
                ProgressUpdate.CLUSTERING_STAGE.CLUSTERING, completedJobs, totalJobs);

        for (IProgressListener progressListener : progressListeners) {
            progressListener.onProgressUpdate(progressUpdate);
        }
    }
}
