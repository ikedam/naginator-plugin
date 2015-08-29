package com.chikli.hudson.plugin.naginator;

import hudson.matrix.MatrixRun;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Used from {@link NaginatorPublisher} to mark a build to be reshceduled.
 */
public class NaginatorPublisherScheduleAction extends NaginatorScheduleAction {
    private static final Logger LOGGER = Logger.getLogger(NaginatorPublisherScheduleAction.class.getName());
    
    private final String regexpForRerun;
    private final boolean rerunIfUnstable;
    private final boolean checkRegexp;
    
    public NaginatorPublisherScheduleAction(NaginatorPublisher publisher) {
        super(publisher.getMaxSchedule(), publisher.getDelay(), publisher.isRerunMatrixPart());
        this.regexpForRerun = publisher.getRegexpForRerun();
        this.rerunIfUnstable = publisher.isRerunIfUnstable();
        this.checkRegexp = publisher.isCheckRegexp();
    }
    
    @Nullable
    public String getRegexpForRerun() {
        return regexpForRerun;
    }
    
    public boolean isRerunIfUnstable() {
        return rerunIfUnstable;
    }
    
    public boolean isCheckRegexp() {
        return checkRegexp;
    }
    
    @Override
    public boolean shouldSchedule(@Nonnull Run<?, ?> run, @Nonnull TaskListener listener, int retryCount) {
        if ((run.getResult() == Result.SUCCESS) || (run.getResult() == Result.ABORTED)) {
            return false;
        }
        
        // If we're not set to rerun if unstable, and the build's unstable, return true.
        if ((!isRerunIfUnstable()) && (run.getResult() == Result.UNSTABLE)) {
            return false;
        }
        
        // If we're supposed to check for a regular expression in the build output before
        // scheduling a new build, do so.
        if (isCheckRegexp()) {
            LOGGER.log(Level.FINEST, "Got checkRegexp == true");
            
            String regexpForRerun = getRegexpForRerun();
            if ((regexpForRerun != null) && (!regexpForRerun.equals(""))) {
                LOGGER.log(Level.FINEST, "regexpForRerun - {0}", regexpForRerun);
                
                try {
                    // If parseLog returns false, we didn't find the regular expression,
                    // so return true.
                    if (!parseLog(run.getLogFile(), regexpForRerun)) {
                        LOGGER.log(Level.FINEST, "regexp not in logfile");
                        return false;
                    }
                } catch (IOException e) {
                    e.printStackTrace(listener
                                      .error("error while parsing logs for naginator - forcing rebuild."));
                }
            }
        }
        
        return super.shouldSchedule(run, listener, retryCount);
    }

    @Override
    public boolean shouldScheduleForMatrixRun(@Nonnull MatrixRun run, @Nonnull TaskListener listener) {
        if ((run.getResult() == Result.SUCCESS) || (run.getResult() == Result.ABORTED)) {
            return false;
        }
        if ((!isRerunIfUnstable()) && (run.getResult() == Result.UNSTABLE)) {
            return false;
        }
        return true;
    }
    
    private boolean parseLog(File logFile, @Nonnull String regexp) throws IOException {
        // TODO annotate `logFile` with `@Nonnull`
        // after upgrading the target Jenkins to 1.568 or later.

        // Assume default encoding and text files
        String line;
        Pattern pattern = Pattern.compile(regexp);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(logFile));
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    return true;
                }
            }
            return false;
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
}