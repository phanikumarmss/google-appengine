package com.google.appengine.googleappengine;
import com.google.jenkins.plugins.credentials.oauth.GoogleOAuth2ScopeRequirement;
import java.util.Collection;;
public class Scope extends GoogleOAuth2ScopeRequirement {
	@Override
	public Collection<String> getScopes() {
		return null;
	}
}