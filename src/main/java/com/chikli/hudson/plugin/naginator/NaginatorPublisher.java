package com.chikli.hudson.plugin.naginator;

import hudson.Extension;
import hudson.Launcher;
import hudson.matrix.MatrixRun;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reschedules a build if the current one fails.
 *
 * @author Nayan Hajratwala <nayan@chikli.com>
 */
public class NaginatorPublisher extends Notifier {
    private final String regexpForRerun;
    private final boolean rerunIfUnstable;
    private final boolean rerunMatrixPart;
    private final boolean checkRegexp;

    private ScheduleDelay delay;

    private int maxSchedule;

    // backward compatible constructor
    public NaginatorPublisher(String regexpForRerun,
                              boolean rerunIfUnstable,
                              boolean checkRegexp) {
        this(regexpForRerun, rerunIfUnstable, false, checkRegexp, 0, new ProgressiveDelay(5*60, 3*60*60));
    }

    @DataBoundConstructor
    public NaginatorPublisher(String regexpForRerun,
                              boolean rerunIfUnstable,
                              boolean rerunMatrixPart,
                              boolean checkRegexp,
                              int maxSchedule,
                              ScheduleDelay delay) {
        this.regexpForRerun = regexpForRerun;
        this.rerunIfUnstable = rerunIfUnstable;
        this.rerunMatrixPart = rerunMatrixPart;
        this.checkRegexp = checkRegexp;
        this.maxSchedule = maxSchedule;
        this.delay = delay;
    }

    public Object readResolve() {
        if (this.delay == null) {
            // Backward compatibility : progressive 5 minutes up to 3 hours
            delay = new ProgressiveDelay(5*60, 3*60*60);
        }
        return this;
    }

    public boolean isRerunIfUnstable() {
        return rerunIfUnstable;
    }

    public boolean isRerunMatrixPart() {
        return rerunMatrixPart;
    }
    
    public boolean isCheckRegexp() {
        return checkRegexp;
    }

    public String getRegexpForRerun() {
        return regexpForRerun;
    }

    public ScheduleDelay getDelay() {
        return delay;
    }

    public int getMaxSchedule() {
        return maxSchedule;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        if (!(build instanceof MatrixRun)) {
            build.addAction(new Action(this));
        } else {
            MatrixBuild parent = ((MatrixRun)build).getParentBuild();
            if (parent.getAction(Action.class) == null) {
                // No strict exclusion is required
                // as it doesn't matter if the action gets duplicated.
                parent.addAction(new Action(this));
            }
        }
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }


    @Override
    public DescriptorImpl getDescriptor() {
        // see Descriptor javadoc for more about what a descriptor is.
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public final static NaginatorListener LISTENER = new NaginatorListener();


    /**
     * Descriptor for {@link NaginatorPublisher}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>views/hudson/plugins/naginator/NaginatorBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(NaginatorPublisher.class);
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Retry build after failure";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        /**
         * Creates a new instance of {@link NaginatorPublisher} from a submitted form.
         */
        @Override
        public Notifier newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return req.bindJSON(NaginatorPublisher.class, formData);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(NaginatorPublisher.class.getName());

    public static class Action extends NaginatorScheduleAction {
        private final String regexpForRerun;
        private final boolean rerunIfUnstable;
        private final boolean checkRegexp;
        
        public Action(NaginatorPublisher publisher) {
            super(publisher.getMaxSchedule(), publisher.getDelay(), publisher.isRerunMatrixPart());
            this.regexpForRerun = publisher.getRegexpForRerun();
            this.rerunIfUnstable = publisher.isRerunIfUnstable();
            this.checkRegexp = publisher.isCheckRegexp();
        }
        
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
        public boolean shouldSchedule(Run<?, ?> run, TaskListener listener, int retryCount) {
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
                if ((regexpForRerun !=null) && (!regexpForRerun.equals(""))) {
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
        public boolean shouldScheduleForMatrixRun(MatrixRun run, TaskListener listener) {
            if ((run.getResult() == Result.SUCCESS) || (run.getResult() == Result.ABORTED)) {
                return false;
            }
            if ((!isRerunIfUnstable()) && (run.getResult() == Result.UNSTABLE)) {
                return false;
            }
            return true;
        }
        
        private boolean parseLog(File logFile, String regexp) throws IOException {

            if (regexp == null) {
                return false;
            }

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
            }
            finally {
              if(reader != null)
                reader.close();
            }
        }
    }
}
