package fr.herverenault.selfhostedgpstracker;

import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

import javax.net.ssl.SSLHandshakeException;

public class SelfHostedGPSTrackerService extends IntentService implements LocationListener {

    public static final String NOTIFICATION = "fr.herverenault.selfhostedgpstracker";

    public static boolean isRunning;
    public static Calendar runningSince;
    public static String lastServerResponse;

    public Calendar stoppedOn;

    private final static String MY_TAG = "SelfHostedGPSTrackerSrv";

    private SharedPreferences preferences;
    private String urlText;
    private LocationManager locationManager;
    private int pref_gps_updates;
    private long latestUpdate;
    private int pref_max_run_time;
    private boolean pref_timestamp;

    public SelfHostedGPSTrackerService() {
        super("SelfHostedGPSTrackerService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(MY_TAG, "in onCreate, init GPS stuff");

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            onProviderEnabled(LocationManager.GPS_PROVIDER);
        } else {
            onProviderDisabled(LocationManager.GPS_PROVIDER);
        }

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong("stoppedOn", 0);
        editor.commit();
        pref_gps_updates = Integer.parseInt(preferences.getString("pref_gps_updates", "30")); // seconds
        pref_max_run_time = Integer.parseInt(preferences.getString("pref_max_run_time", "24")); // hours
        pref_timestamp = preferences.getBoolean("pref_timestamp", false);
        urlText = preferences.getString("URL", "");
        if (urlText.contains("?")) {
            urlText = urlText + "&";
        } else {
            urlText = urlText + "?";
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, pref_gps_updates * 1000, 1, this);

        lastServerResponse = getResources().getString(R.string.waiting_for_gps_data);
        Intent notifIntent = new Intent(NOTIFICATION);
        notifIntent.putExtra(NOTIFICATION, "START");
        sendBroadcast(notifIntent);

        new SelfHostedGPSTrackerRequest().start("tracker=start");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(MY_TAG, "in onHandleIntent, run for maximum time set in preferences");

        isRunning = true;
        runningSince = Calendar.getInstance();
        Intent notifIntent = new Intent(NOTIFICATION);
        sendBroadcast(notifIntent);



        long endTime = System.currentTimeMillis() + pref_max_run_time*60*60*1000;
        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(60*1000); // note: when device is sleeping, it may last up to 5 minutes or more
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void onDestroy() {
        // (user clicked the stop button, or max run time has been reached)
        Log.d(MY_TAG, "in onDestroy, stop listening to the GPS");
        new SelfHostedGPSTrackerRequest().start("tracker=stop");

        locationManager.removeUpdates(this);

        isRunning = false;
        stoppedOn = Calendar.getInstance();

        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong("stoppedOn", stoppedOn.getTimeInMillis());
        editor.commit();

        Intent notifIntent = new Intent(NOTIFICATION);
        sendBroadcast(notifIntent);
    }

    /* -------------- GPS stuff -------------- */

    @Override
    public void onLocationChanged(Location location) {
        Log.d(MY_TAG, "in onLocationChanged, latestUpdate == " + latestUpdate);
        long currentTime = System.currentTimeMillis();

        // Tolerate devices which sometimes send GPS updates 1 second too early,
        // such as HTC One Mini...
        if ((currentTime - latestUpdate) < (pref_gps_updates - 1) * 1000) {
            return;
        }

        latestUpdate = currentTime;

        new SelfHostedGPSTrackerRequest().start(
                "latitude=" + location.getLatitude()
                        + "&longitude=" + location.getLongitude()
                        + "&altitude=" + location.getAltitude()
                        + "&bearing=" + location.getBearing()
                        + "&speed=" + location.getSpeed()
                        + "&time=" + location.getTime()
                        + "&name=" + android.os.Build.MODEL
                        + ( pref_timestamp ? "&t=" + currentTime : "" )
        );
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private class SelfHostedGPSTrackerRequest extends Thread {
        private final static String MY_TAG = "SelfHostedGPSTrackerReq";
        private String params;

        public void run() {
            String message;
            int code = 0;

            try {
                URL url = new URL(urlText + params);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(10000 /* milliseconds */);
                conn.setConnectTimeout(15000 /* milliseconds */);
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.connect();
                code = conn.getResponseCode();
                Log.d(MY_TAG, "HTTP request done: " + code);
                message = "HTTP " + code;
            }
            catch (MalformedURLException e) {
                message = getResources().getString(R.string.error_malformed_url);
            }
            catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            }
            catch (SSLHandshakeException e) {
                message = getResources().getString(R.string.error_ssl);
            }
            catch (SocketTimeoutException e) {
                message = getResources().getString(R.string.error_timeout);
            }
            catch (Exception e) {
                Log.d(MY_TAG, "HTTP request failed: " + e);
                message = e.getLocalizedMessage();
                if (message == null) {
                    message = e.toString();
                }
            }

            if ( ! params.startsWith("tracker=")) {
                lastServerResponse = getResources().getString(R.string.last_location_sent_at)
                        + " "
                        + DateFormat.getTimeInstance().format(new Date())
                        + " ";

                if (code == 200) {
                    lastServerResponse += "<font color='#00aa00'><b>"
                            + getResources().getString(R.string.http_request_ok)
                            + "</b></font>";
                } else {
                    lastServerResponse += "<font color='#ff0000'><b>"
                            + getResources().getString(R.string.http_request_failed)
                            + "</b></font>"
                            + "<br>"
                            + "(" + message + ")";
                }

                Intent notifIntent = new Intent(NOTIFICATION);
                notifIntent.putExtra(NOTIFICATION, "HTTP");
                sendBroadcast(notifIntent);
            }
        }

        public void start(String params) {
            try {
                this.params = URLEncoder.encode(params, "utf-8").replace("%3D", "=").replace("%26", "&");
            } catch (UnsupportedEncodingException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            super.start();
        }
    }
}
