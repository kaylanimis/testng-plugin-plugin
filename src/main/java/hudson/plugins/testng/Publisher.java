package hudson.plugins.testng;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.plugins.testng.results.TestResults;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * This class defines a @Publisher and @Extension
 * 
 */
public class Publisher extends Recorder {

	public final String reportFilenamePattern;
	/**
	 * @deprecated since v0.23. not used anymore. Here to ensure installed
	 *             versions of plugin are not affected
	 */
	private boolean isRelativePath;
	public final boolean escapeTestDescp;
	public final boolean escapeExceptionMsg;
	public final boolean skipsAreUnstable;

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	@DataBoundConstructor
	public Publisher(String reportFilenamePattern, boolean escapeTestDescp,
			boolean escapeExceptionMsg, boolean skipsAreUnstable) {
		reportFilenamePattern.getClass();
		this.reportFilenamePattern = reportFilenamePattern;
		this.escapeTestDescp = escapeTestDescp;
		this.escapeExceptionMsg = escapeExceptionMsg;
		this.skipsAreUnstable = skipsAreUnstable;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.STEP;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BuildStepDescriptor<hudson.tasks.Publisher> getDescriptor() {
		return DESCRIPTOR;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<? extends Action> getProjectActions(
			AbstractProject<?, ?> project) {
		Collection<Action> actions = new ArrayList<Action>();
		actions.add(new TestNGProjectAction(project, escapeTestDescp,
				escapeExceptionMsg));
		return actions;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			final BuildListener listener) throws InterruptedException,
			IOException {

		PrintStream logger = listener.getLogger();
		logger.println("Looking for TestNG results report in workspace using pattern: "
				+ reportFilenamePattern);
		FilePath[] paths = locateReports(build.getWorkspace(),
				reportFilenamePattern);

		if (paths.length == 0) {
			logger.println("Did not find any matching files.");
			build.setResult(Result.FAILURE);
			// build can still continue
			return true;
		}

		boolean filesSaved = saveReports(getTestNGReport(build), paths);
		if (!filesSaved) {
			logger.println("Failed to save TestNG XML reports");
			build.setResult(Result.FAILURE);
			return true;
		}

		TestResults results = TestNGBuildAction.loadResults(build);

		if (results.getTestList().size() > 0) {
			// create an individual report for all of the results and add it to
			// the build
			TestNGBuildAction action = new TestNGBuildAction(build, results);
			build.getActions().add(action);
			if (results.getFailedConfigCount() > 0
					|| (skipsAreUnstable && results.getSkippedConfigCount() > 0)
					|| results.getFailedTestCount() > 0
					|| (skipsAreUnstable && results.getSkippedTestCount() > 0)) {
				build.setResult(Result.UNSTABLE);
			}
		} else {
			logger.println("Found matching files but did not find any TestNG results.");
			build.setResult(Result.FAILURE);
			return true;
		}

		return true;
	}

	/**
	 * look for testng reports based in the configured parameter includes.
	 * 'filenamePattern' is - an Ant-style pattern - a list of files and folders
	 * separated by the characters ;:,
	 * 
	 * NOTE: based on how things work for emma plugin for jenkins
	 */
	static FilePath[] locateReports(FilePath workspace, String filenamePattern)
			throws IOException, InterruptedException {

		// First use ant-style pattern
		try {
			FilePath[] ret = workspace.list(filenamePattern);
			if (ret.length > 0) {
				return ret;
			}
		} catch (Exception e) {
		}

		// If it fails, do a legacy search
		List<FilePath> files = new ArrayList<FilePath>();
		String parts[] = filenamePattern.split("\\s*[;:,]+\\s*");
		for (String path : parts) {
			FilePath src = workspace.child(path);
			if (src.exists()) {
				if (src.isDirectory()) {
					files.addAll(Arrays.asList(src.list("**/testng*.xml")));
				} else {
					files.add(src);
				}
			}
		}
		return files.toArray(new FilePath[files.size()]);
	}

	/**
	 * Gets the directory to store report files
	 */
	static FilePath getTestNGReport(AbstractBuild<?, ?> build) {
		return new FilePath(new File(build.getRootDir(), "testng"));
	}

	static boolean saveReports(FilePath testngDir, FilePath[] paths) {
		try {
			testngDir.mkdirs();
			int i = 0;
			for (FilePath report : paths) {
				String name = "testng-results" + (i > 0 ? "-" + i : "")
						+ ".xml";
				i++;
				FilePath dst = testngDir.child(name);
				report.copyTo(dst);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
		return true;
	}

	public static final class DescriptorImpl extends
			BuildStepDescriptor<hudson.tasks.Publisher> {

		/**
		 * Do not instantiate DescriptorImpl.
		 */
		private DescriptorImpl() {
			super(Publisher.class);
		}

		/**
		 * {@inheritDoc}
		 */
		public String getDisplayName() {
			return "Publish " + PluginImpl.DISPLAY_NAME;
		}

		@Override
		public hudson.tasks.Publisher newInstance(StaplerRequest req,
				JSONObject formData) throws FormException {
			return req.bindJSON(Publisher.class, formData);
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}
	}

}