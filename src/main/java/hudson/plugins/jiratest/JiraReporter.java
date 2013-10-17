package hudson.plugins.jiratest;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.FormValidation;

import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import jenkins.model.Jenkins;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;

public class JiraReporter extends Notifier {

    public String projectKey;
    public String serverAddress;
    public String username;
    public String password;

    public boolean debugFlag;
    public boolean verboseDebugFlag;
    public boolean createAllFlag;

    private FilePath workspace;

    private static final String PluginName = new String(
	    "[JiraTestResultReporter]");
    private final String pInfo = String.format("%s [INFO]", PluginName);
    private final String pDebug = String.format("%s [DEBUG]", PluginName);
    private final String pVerbose = String.format("%s [DEBUGVERBOSE]",
	    PluginName);
    private final String prefixError = String.format("%s [ERROR]", PluginName);

    @DataBoundConstructor
    public JiraReporter(String projectKey, String serverAddress,
	    String username, String password, boolean createAllFlag,
	    boolean debugFlag, boolean verboseDebugFlag) {
	if (serverAddress.endsWith("/")) {
	    this.serverAddress = serverAddress;
	} else {
	    this.serverAddress = serverAddress + "/";
	}

	this.projectKey = projectKey;
	this.username = username;
	this.password = password;

	this.verboseDebugFlag = verboseDebugFlag;
	if (verboseDebugFlag) {
	    this.debugFlag = true;
	} else {
	    this.debugFlag = debugFlag;
	}

	this.createAllFlag = createAllFlag;
    }

    // @Override
    public BuildStepMonitor getRequiredMonitorService() {
	return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(final AbstractBuild build, final Launcher launcher,
	    final BuildListener listener) {
	PrintStream logger = listener.getLogger();
	logger.printf("%s Examining test results...%n", pInfo);
	debugLog(listener, String.format("Build result is %s%n", build
		.getResult().toString()));
	this.workspace = build.getWorkspace();
	debugLog(
		listener,
		String.format("%s Workspace is %s%n", pInfo,
			this.workspace.toString()));
	// if (build.getResult() == Result.UNSTABLE) {
	AbstractTestResultAction<?> testResultAction = build
		.getTestResultAction();
	List<CaseResult> failedTests = testResultAction.getFailedTests();
	printResultItems(failedTests, listener);
	createJiraIssue(failedTests, listener, build);
	// }
	logger.printf("%s Done.%n", pInfo);
	return true;
    }

    private void printResultItems(final List<CaseResult> failedTests,
	    final BuildListener listener) {
	if (!this.debugFlag) {
	    return;
	}
	PrintStream out = listener.getLogger();
	for (CaseResult result : failedTests) {
	    out.printf("%s projectKey: %s%n", pDebug, this.projectKey);
	    out.printf("%s errorDetails: %s%n", pDebug,
		    result.getErrorDetails());
	    out.printf("%s fullName: %s%n", pDebug, result.getFullName());
	    out.printf("%s simpleName: %s%n", pDebug, result.getSimpleName());
	    out.printf("%s title: %s%n", pDebug, result.getTitle());
	    out.printf("%s packageName: %s%n", pDebug, result.getPackageName());
	    out.printf("%s name: %s%n", pDebug, result.getName());
	    out.printf("%s className: %s%n", pDebug, result.getClassName());
	    out.printf("%s failedSince: %d%n", pDebug, result.getFailedSince());
	    out.printf("%s status: %s%n", pDebug, result.getStatus().toString());
	    out.printf("%s age: %s%n", pDebug, result.getAge());
	    out.printf("%s ErrorStackTrace: %s%n", pDebug,
		    result.getErrorStackTrace());

	    String affectedFile = result.getErrorStackTrace().replace(
		    this.workspace.toString(), "");
	    out.printf("%s affectedFile: %s%n", pDebug, affectedFile);
	    out.printf("%s ----------------------------%n", pDebug);
	}
    }

    void debugLog(final BuildListener listener, final String message) {
	if (!this.debugFlag) {
	    return;
	}
	PrintStream logger = listener.getLogger();
	logger.printf("%s %s%n", pDebug, message);
    }

    void createJiraIssue(final List<CaseResult> failedTests,
	    final BuildListener listener, final AbstractBuild build) {
	PrintStream logger = listener.getLogger();
	String url = this.serverAddress + "rest/api/2/issue/";
	JiraSession session = null;
	try {
	    session = new JiraSession(new URL(this.serverAddress));
	    session.connect(username, password);
	} catch (MalformedURLException e1) {
	    throw new RuntimeException(this.prefixError
		    + " Failed with error message  : " + e1.getMessage());
	} catch (RemoteException e) {
	    throw new RuntimeException(this.prefixError
		    + " Failed with error message  : " + e.getMessage());
	}

	for (CaseResult result : failedTests) {
	    if ((result.getAge() == 1) || (this.createAllFlag)) {
		// if (result.getAge() > 0) {
		debugLog(listener, String.format(
			"Creating issue in project %s at URL %s%n",
			this.projectKey, url));
		JiraRestClient client = session.getJiraRestClient();
		IssueRestClient issues = client.getIssueClient();
		// TODO: Get correct issueKey
		IssueInputBuilder builder = new IssueInputBuilder(projectKey,
			(long) 0);
		builder.setSummary("The test " + result.getName() + " failed "
			+ result.getClassName() + ": "
			+ result.getErrorDetails());
		builder.setDescription("Build "
			+ Jenkins.getInstance().getRootUrlFromRequest()
			+ build.getUrl() // TODO: Create correct url
			+ "\r\n Test class: "
			+ result.getClassName()
			+ " -- "
			+ result.getErrorStackTrace().replace(
				this.workspace.toString(), ""));
		IssueInput input = builder.build();
		try {
		    issues.createIssue(input).get();
		} catch (InterruptedException e) {
		    throw new RuntimeException(this.prefixError
			    + " Failed with error message  : " + e.getMessage());
		} catch (ExecutionException e) {
		    throw new RuntimeException(this.prefixError
			    + " Failed with error message  : " + e.getMessage());
		}
	    } else {
		logger.printf("%s This issue is old; not reporting.%n", pInfo);
	    }
	}
    }

    @Override
    public DescriptorImpl getDescriptor() {
	return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends
	    BuildStepDescriptor<Publisher> {

	@Override
	public boolean isApplicable(
		final Class<? extends AbstractProject> jobType) {
	    return true;
	}

	@Override
	public String getDisplayName() {
	    return "Jira Test Result Reporter";
	}

	public FormValidation doCheckProjectKey(@QueryParameter String value) {
	    if (value.isEmpty()) {
		return FormValidation.error("You must provide a project key.");
	    } else {
		return FormValidation.ok();
	    }
	}

	public FormValidation doCheckServerAddress(@QueryParameter String value) {
	    if (value.isEmpty()) {
		return FormValidation.error("You must provide an URL.");
	    }

	    try {
		new URL(value);
	    } catch (final MalformedURLException e) {
		return FormValidation.error("This is not a valid URL.");
	    }

	    return FormValidation.ok();
	}
    }
}
