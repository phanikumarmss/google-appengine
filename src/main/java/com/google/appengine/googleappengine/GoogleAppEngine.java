package com.google.appengine.googleappengine;


import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import com.google.appengine.*;
import com.google.api.client.json.jackson.JacksonFactory;
import com.google.jenkins.plugins.credentials.domains.RequiresDomain;
import com.google.jenkins.plugins.credentials.oauth.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;
import javax.servlet.ServletException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;

import com.cloudbees.plugins.credentials.CredentialsProvider;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link GoogleAppEngine} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #command})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform} method will be invoked. 
 *
 * @author Kohsuke Kawaguchi
 */
@RequiresDomain(value = Scope.class)
public class GoogleAppEngine extends Builder implements SimpleBuildStep {

    private final String command;
    private final String credentialsId;
    private String accountId;
    private TaskListener listener1;
    private Launcher launcherl;
    private FilePath tempDir;
	private final boolean stop;
	private final boolean delete;
	String desciption="";
	String versionID="";
	String currentVersionID="";
	private String service;
    Map<String, String> data;
    // Fields in config.jelly must match the parameter commands in the "DataBoundConstructor"
    @DataBoundConstructor
    public GoogleAppEngine(String command,String credentialsId, boolean stop, String service, boolean delete) {
        this.command = command;
        this.credentialsId = credentialsId;
		this.stop=stop;
		this.service=service;
		this.delete=delete;
    }
    /**
     * We'll use this from the {@code config.jelly}.
     */
    public String getCommand()
	{
        return command;
    }
	public boolean getStop()
	{
        return stop;
    }
	public String getService()
	{
        return service;
    }
	public boolean getDelete()
	{
        return delete;
    }
	public String getCredentialsId() {
        return credentialsId;
    }
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException 
	{
        listener1=listener;
        listener.getLogger().println("Following Operations will be performed");
        listener.getLogger().println("Previous servers will be stopped "+stop);
        listener.getLogger().println("Delete old version on successful deploy of new version "+delete);
        listener.getLogger().println("Service that will be stopped or deleted "+service);
        FilePath workspace=build.getWorkspace();
    	final GoogleRobotPrivateKeyCredentials credential = CredentialsProvider.findCredentialById(credentialsId,GoogleRobotPrivateKeyCredentials.class,build,new Scope());
		tempDir = workspace.createTempDir("gcloud", "config");
    	final ServiceAccountConfig serviceAccountConfig = credential.getServiceAccountConfig();
    	accountId = serviceAccountConfig.getAccountId();
    	final String tmpKeyFile = getKeyFile(serviceAccountConfig, tempDir);
        data= new HashMap<String, String>();
        data.put("CLOUDSDK_CONFIG", tempDir.getRemote());
        listener.getLogger().println("Project ID is "+credential.getProjectId());
        data.put("CLOUDSDK_CORE_PROJECT", credential.getProjectId());
        data.put("GOOGLE_APPLICATION_CREDENTIALS", tmpKeyFile);
        boolean buildStatus=true;
        buildStatus=activate(tmpKeyFile,listener,launcher);
        if (!buildStatus)
        {
        	tempDir.deleteRecursive();
        	return buildStatus;
        }
		if(stop)
		{
			buildStatus=getVersionID(build,workspace.getRemote(), launcher, listener, tempDir);
			if (!buildStatus)
			{
				tempDir.deleteRecursive();
				return buildStatus;
			}
		}
		buildStatus=executeGCloudCLI(build,workspace.getRemote(), launcher, listener, tempDir);
		if (!buildStatus)
		{
			tempDir.deleteRecursive();
			return buildStatus;
		}
		buildStatus=getCurrentVersionID(build,workspace.getRemote(), launcher, listener, tempDir);
		if (!buildStatus)
		{
			tempDir.deleteRecursive();
			return buildStatus;
		}
		buildStatus=splitTraffic(build,workspace.getRemote(), launcher, listener, tempDir);
		if (!buildStatus)
		{
			tempDir.deleteRecursive();
			return buildStatus;
		}
		if(stop)
		{
			buildStatus=stopPrevious(build,workspace.getRemote(), launcher, listener, tempDir);
			if (!buildStatus)
			{
				tempDir.deleteRecursive();
				return buildStatus;
			}
		}
		if(delete)
		{
			buildStatus=deletePrevious(build,workspace.getRemote(), launcher, listener, tempDir);
			if (!buildStatus)
			{
				tempDir.deleteRecursive();
				return buildStatus;
			}
		}
		listener.getLogger().println("Google Cloud Operation completed Successfully"+buildStatus+"\t"+tempDir.getRemote());
        tempDir.deleteRecursive();
		return buildStatus;
    }
	boolean activate(String tmpKeyFile,BuildListener listener,Launcher launcher) throws IOException, InterruptedException 
	{		
		String exec = "gcloud";
		final String authCmd = exec + " auth activate-service-account " + accountId + " --key-file \"" + tmpKeyFile + "\"";
		try
		{
			int retCode = launcher.launch()
					.cmdAsSingleString(authCmd)
					.stdout(listener1.getLogger())
					.envs(data)
					.join();
			if(retCode==0)
				listener.getLogger().println("Service Account configuration completed successfully");
			if (retCode != 0) {
				return false;
			}
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return true;
	}
	private String getKeyFile(ServiceAccountConfig serviceAccount, FilePath configDir)
	{
		FilePath tmpKeyFile=null;
		try {
			if (serviceAccount instanceof JsonServiceAccountConfig) {
				String keyFilePath = ((JsonServiceAccountConfig)serviceAccount).getJsonKeyFile();
				JsonKey key = JsonKey.load(new JacksonFactory(), new FileInputStream(new File(keyFilePath)));
				tmpKeyFile=configDir.createTempFile("google", "key");
				tmpKeyFile.write(key.toPrettyString(),"UTF-8");
				listener1.getLogger().println("Successfully created temporary key file");
			} else if (serviceAccount instanceof P12ServiceAccountConfig) {
				String keyFilePath = ((P12ServiceAccountConfig)serviceAccount).getP12KeyFile();
				tmpKeyFile = configDir.createTempFile("gcloud", "key");
				tmpKeyFile.write(keyFilePath,"UTF-8");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		return tmpKeyFile.getRemote();
	}
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }
	private boolean getVersionID(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException {
    	int retCode=1;
    	try
		{
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if(service.length()<1)
				service="default";
    		retCode= launcher.launch()
					.pwd(workspace)
					.cmdAsSingleString("gcloud app services describe "+service+" --format text")
					.stdout(baos)
	                .envs(data)
	                .join();
			String desciption=baos.toString();
			baos.close();
			versionID=desciption.substring(desciption.indexOf("split.allocations.")+18,desciption.lastIndexOf(':'));
			listener.getLogger().println("Stopping Version "+versionID);
			if (retCode!=0)
				if(build.getLog().contains("GCLOUD: ERROR: (gcloud.app.deploy) The requested resource already exists"))
					return true;
			
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return retCode==0?true:false;
	}
	private boolean getCurrentVersionID(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException {
    	int retCode=1;
    	try
		{
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			if(service.length()<1)
				service="default";
    		retCode= launcher.launch()
					.pwd(workspace)
					.cmdAsSingleString("gcloud app services describe "+service+" --format text")
					.stdout(baos)
	                .envs(data)
	                .join();
			String desciption=baos.toString();
			baos.close();
			currentVersionID=desciption.substring(desciption.indexOf("split.allocations.")+18,desciption.lastIndexOf(':'));
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return retCode==0?true:false;
	}
    private boolean executeGCloudCLI(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException {
    	int retCode=1;
    	try
		{
    		retCode= launcher.launch()
					.pwd(workspace)
					.cmdAsSingleString(command)
					.stdout(listener.getLogger())
	                .envs(data)
	                .join();
			if (retCode!=0)
				if(build.getLog().contains("GCLOUD: ERROR: (gcloud.app.deploy) The requested resource already exists"))
					return true;
			
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return retCode==0?true:false;
	}
	private boolean stopPrevious(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException 
	{
    	int retCode=1;
		for(int i=0;i<3&&retCode==1;i++)
		{
			try
			{
				try 
				{
					Thread.sleep(30000);
				} 
				catch (InterruptedException e1) 
				{
					e1.printStackTrace();
				}
				String stopService="gcloud app versions stop --service "+service+" "+versionID+" --quiet";
				listener.getLogger().println("Stopping previous service "+service+" with version "+versionID);
				retCode= launcher.launch()
						.pwd(workspace)
						.cmdAsSingleString(stopService)
						.stdout(listener.getLogger())
						.envs(data)
						.join();
			}
			catch(Exception e)
			{
				listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
				e.printStackTrace();
			}
		}
		return retCode==0?true:false;
	}
	private boolean deletePrevious(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException 
	{
    	int retCode=1;
    	try
		{
			String deleteService="gcloud app versions delete --service "+service+" "+versionID+" --quiet";
			listener.getLogger().println("Deleting previous service "+service+" with version "+versionID);
    		retCode= launcher.launch()
					.pwd(workspace)
					.cmdAsSingleString(deleteService)
					.stdout(listener.getLogger())
	                .envs(data)
	                .join();
			if (retCode!=0)
				if(build.getLog().contains("GCLOUD: ERROR: (gcloud.app.deploy) The requested resource already exists"))
					return true;
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return retCode==0?true:false;
	}
	private boolean splitTraffic(AbstractBuild build,String workspace, Launcher launcher, TaskListener listener, FilePath configDir) throws IOException, InterruptedException 
	{
    	int retCode=1;
    	try
		{
			String deleteService="gcloud app services set-traffic "+service+" --splits "+currentVersionID+"=1 --quiet";
			listener.getLogger().println("Serving all traffic to new version "+currentVersionID);
    		retCode= launcher.launch()
					.pwd(workspace)
					.cmdAsSingleString(deleteService)
					.stdout(listener.getLogger())
	                .envs(data)
	                .join();
		}
		catch(Exception e)
		{
			listener.getLogger().println("Exception raised"+e.getMessage()+"\n"+e.toString()+"\n"+e.getCause());
			e.printStackTrace();
		}
		return retCode==0?true:false;
	}
    /**
     * Descriptor for {@link GoogleAppEngine}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See {@code src/main/resources/hudson/plugins/hello_world/GoogleAppEngine/*.jelly}
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use {@code transient}.
         */
        private boolean useFrench;

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() 
        {
            load();
        }

        /**
         * Performs on-the-fly validation of the form field 'command'.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckCommand(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please Provide your command");
            if (value.length() < 4)
                return FormValidation.warning("Isn't the command too short?");
            return FormValidation.ok();
        }
		public FormValidation doCheckCredentialsId(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please select credentials ID");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable command is used in the configuration screen.
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            useFrench = formData.getBoolean("useFrench");
            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)
            save();
            return super.configure(req,formData);
        }

        /**
         * This method returns true if the global configuration says we should speak French.
         *
         * The method command is bit awkward because global.jelly calls this method to determine
         * the initial state of the checkbox by the naming convention.
         */
        public boolean getUseFrench() {
            return useFrench;
        }

		@Override
		public String getDisplayName() {
			// TODO Auto-generated method stub
			return "Google App Engine";
		}
    }
	@Override
	public void perform(Run<?, ?> arg0, FilePath arg1, Launcher arg2, TaskListener arg3)
			throws InterruptedException, IOException {
		// TODO Auto-generated method stub
	}
}