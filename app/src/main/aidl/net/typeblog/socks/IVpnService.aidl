package net.typeblog.socks;

import android.os.Bundle;

interface IVpnService
{
	boolean isRunning();
	void stop();
	String getCurrentIp();
	String getCountryCode();
	String getCountry();
	String getRegion();
	String getCity();
	String getIsp();
	String getOrg();
	String getAsName();
	String getTimezone();
	long getConnectedSince();
	String getErrorMessage();
	long getReceivedBytes();
	long getSentBytes();
	String getProfileName();
	boolean isProxyVerified();
	Bundle getState();
}
