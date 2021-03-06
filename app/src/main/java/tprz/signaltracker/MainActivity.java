package tprz.signaltracker;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.splunk.mint.Mint;

import org.json.JSONException;
import org.json.JSONObject;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.json.IJsonObject;
import edu.mit.media.funf.pipeline.BasicPipeline;
import edu.mit.media.funf.probe.Probe;
import edu.mit.media.funf.probe.builtin.HardwareInfoProbe;
import edu.mit.media.funf.probe.builtin.WifiProbe;
import it.gmariotti.cardslib.library.internal.CardHeader;
import it.gmariotti.cardslib.library.internal.CardThumbnail;
import it.gmariotti.cardslib.library.view.CardViewNative;
import tprz.signaltracker.location.TubeGraph;

public class MainActivity extends Activity  implements Probe.DataListener{

    public static final String PIPELINE_NAME = "default";
    private FunfManager funfManager;
    private BasicPipeline pipeline;
    private WifiProbe wifiProbe;
    private CellSignalProbe cellSignalProbe;
    private ToggleButton enabledToggle;
    private Button archiveButton, scanNowButton;
    private Handler handler;
    public static final String AUTHORITY = "tprz.signaltracker.provider";
    public static final String ACCOUNT_TYPE = "tomprz.me";
    public static final String ACCOUNT = "dummyaccount1";
    public static final long SECONDS_PER_HOUR = 60L * 60L;
    public static final long SYNC_INTERVAL_IN_HOURS = 6L;
    public static final long SYNC_INTERVAL =
            SYNC_INTERVAL_IN_HOURS *
                    SECONDS_PER_HOUR;

    private ServiceConnection funfManagerConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            funfManager = ((FunfManager.LocalBinder)service).getManager();

            Gson gson = funfManager.getGson();
            wifiProbe = gson.fromJson(new JsonObject(), WifiProbe.class);
            cellSignalProbe = gson.fromJson(new JsonObject(), CellSignalProbe.class);
            BandwidthProbe bandwidthProbe = gson.fromJson(new JsonObject(), BandwidthProbe.class);
            HardwareInfoProbe hardwareInfoProbe = gson.fromJson(new JsonObject(), HardwareInfoProbe.class);
            pipeline = (BasicPipeline) funfManager.getRegisteredPipeline(PIPELINE_NAME);
            wifiProbe.registerPassiveListener(MainActivity.this);
            hardwareInfoProbe.registerPassiveListener(MainActivity.this);
            cellSignalProbe.registerPassiveListener(MainActivity.this);
            bandwidthProbe.registerListener(MainActivity.this);


            // This toggle button enables or disables the pipeline
            enabledToggle.setChecked(pipeline.isEnabled());
            if(enabledToggle.isChecked()) {
                if(!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
            } else {
                if(wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
            autoOff.pipelineEnabled(enabledToggle.isChecked());
            enabledToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (funfManager != null) {
                        autoOff.pipelineEnabled(isChecked);
                        if (isChecked) {
                            funfManager.enablePipeline(PIPELINE_NAME);
                            pipeline = (BasicPipeline) funfManager.getRegisteredPipeline(PIPELINE_NAME);
                            if(cellSignalCard != null) {
                                cellSignalCard.enableCard();
                            }
                            if(stationLocationCard != null) {
                                stationLocationCard.enableCard();
                            }
                            if(!wakeLock.isHeld()) {
                                wakeLock.acquire();
                            }
                        } else {
                            funfManager.disablePipeline(PIPELINE_NAME);
                            BandwidthProbe.cancelDownloads((DownloadManager) getApplicationContext().getSystemService(Context.DOWNLOAD_SERVICE));
                            stopDownloads();
                            if(cellSignalCard != null) {
                                cellSignalCard.disableCard();
                            }
                            if(stationLocationCard != null) {
                                stationLocationCard.disableCard();
                            }
                            if(wakeLock.isHeld()) {
                                wakeLock.release();
                            }
                        }
                    }

                    MultiLogger.isEnabled = enabledToggle.isEnabled();

                    JSONObject enabledToggleData = new JSONObject();
                    try {
                        enabledToggleData.put("enabled", enabledToggle.isEnabled());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    eventLogger.logEvent("Enable Toggle Click", enabledToggleData);

                    eventLogger.logEvent("Enabled button clicked" );
                }
            });

            // Set UI ready to use, by enabling buttons
            enabledToggle.setEnabled(true);
            archiveButton.setEnabled(true);
            scanNowButton.setEnabled(true);
            setupCards();

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            funfManager = null;
        }
    };
    private CellSignalCard cellSignalCard;
    private StationLocationCard stationLocationCard;
    private EventLogger eventLogger;
    private PowerManager.WakeLock wakeLock;
    private AutoOff autoOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Mint.initAndStartSession(MainActivity.this, "65ac44bd");
        setContentView(R.layout.main);

        eventLogger = EventLogger.getInstance(getApplicationContext());

        // Used to make interface changes on main thread
        handler = new Handler();

        enabledToggle = (ToggleButton) findViewById(R.id.enabledToggle);
        enabledToggle.setEnabled(false);

        this.autoOff = new AutoOff(enabledToggle);

        Account account = CreateSyncAccount(this);
        ContentResolver.setSyncAutomatically(account, AUTHORITY, true);
        ContentResolver.addPeriodicSync(
                account,
                AUTHORITY,
                Bundle.EMPTY,
                SYNC_INTERVAL);

        // Runs an archive if pipeline is enabled
        archiveButton = (Button) findViewById(R.id.archiveButton);
        archiveButton.setEnabled(false);
        archiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                JSONObject archiveClickData = new JSONObject();
                try {
                    archiveClickData.put("pipeline_enabled", pipeline.isEnabled());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                eventLogger.logEvent("Archive Button Click", archiveClickData);

                if (pipeline.isEnabled()) {
                    pipeline.onRun(BasicPipeline.ACTION_ARCHIVE, null);

                    // Wait 1 second for archive to finish, then refresh the UI
                    // (Note: this is kind of a hack since archiving is seamless and there are no messages when it occurs)
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getBaseContext(), "Archived!", Toast.LENGTH_SHORT).show();
                            //updateScanCount();
                        }
                    }, 1000L);
                } else {
                    Toast.makeText(getBaseContext(), "Pipeline is not enabled.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Forces the pipeline to scan now
        scanNowButton = (Button) findViewById(R.id.scanNowButton);
        scanNowButton.setEnabled(false);
        scanNowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject archiveClickData = new JSONObject();
                try {
                    archiveClickData.put("pipeline_enabled", pipeline.isEnabled());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                eventLogger.logEvent("Scan Now Button Click", archiveClickData);

                if (pipeline.isEnabled()) {

                    // Manually register the pipeline
                    wifiProbe.registerListener(pipeline);
//                    locationProbe.registerListener(pipeline);
////                    cellTowerProbe.registerListener(pipeline);
                    cellSignalProbe.registerListener(pipeline);
//                    bandwidthProbe.registerListener(pipeline);
//                    hardwareInfoProbe.registerListener(pipeline);
                } else {
                    Toast.makeText(getBaseContext(), "Pipeline is not enabled.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Button syncButton = (Button) findViewById(R.id.sync_button);
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DataReporter dataReporter = DataReporter.getInstance(getApplicationContext());
                dataReporter.sync();

                JSONObject archiveClickData = new JSONObject();
                try {
                    archiveClickData.put("pipeline_enabled", pipeline.isEnabled());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                eventLogger.logEvent("Sync Button Click", archiveClickData);
            }
        });

        // Bind to the service, to create the connection with FunfManager
        bindService(new Intent(this, FunfManager.class), funfManagerConn, BIND_AUTO_CREATE);

        MultiLogger.isEnabled = enabledToggle.isEnabled();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyWakelockTag");
    }

    private void stopDownloads() {
        DownloadManager manager = (DownloadManager) getApplicationContext().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_RUNNING);
            Cursor c = manager.query(query);
            c.moveToFirst();
            while (c.moveToNext()) {
                Long id = c
                        .getLong(c
                                .getColumnIndex(DownloadManager.COLUMN_ID));
                int res = manager.remove(id);
                if(res == 0) {
                    Log.i("BandwidthProbe", "Item was not removed from Download manager");
                } else {
                    Log.i("BandwidthProbe", "Removing download with id: " + id);
                }
            }

    }

    public static Account CreateSyncAccount(Context context) {
        // Create the account type and default account
        Account newAccount = new Account(
                ACCOUNT, ACCOUNT_TYPE);
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(
                        ACCOUNT_SERVICE);
        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
        accountManager.getAccounts();
        if (accountManager.addAccountExplicitly(newAccount, null, null)) {
            /*
             * If you don't set android:syncable="true" in
             * in your <provider> element in the manifest,
             * then call context.setIsSyncable(account, AUTHORITY, 1)
             * here.
             *
             */

            return newAccount;
        } else {
            /*
             * The account exists or some other error occurred. Log this, report it,
             * or handle it internally.
             */
            return newAccount;
        }
    }

    private void setupCards() {

        // Station Location Card
        TubeGraph tubeGraph = DataReporter.getInstance(getApplicationContext()).getTubeGraph();
        stationLocationCard = new StationLocationCard(getApplicationContext(), R.layout.card_location_layout, this, tubeGraph, pipeline.isEnabled());

        CardViewNative stationLocationCardView = (CardViewNative) findViewById(R.id.location_card_view);
       stationLocationCardView.setCard(stationLocationCard);

        //Create a Card
        cellSignalCard = new CellSignalCard(getApplicationContext(), R.layout.cell_signal_thumbnail_layout, stationLocationCard, pipeline.isEnabled());

        CardThumbnail thumbnail = new CardThumbnail(getApplicationContext());
        thumbnail.setDrawableResource(R.drawable.ic_signal_cellular_null_grey600_48dp);
        cellSignalCard.addCardThumbnail(thumbnail);

        //Create a CardHeader
        CardHeader header = new CardHeader(getApplicationContext());
        header.setTitle("Cell Signal Probe");
        //Add Header to card
        cellSignalCard.addCardHeader(header);

        CardViewNative cardView = (CardViewNative) findViewById(R.id.carddemo);

        cardView.setCard(cellSignalCard);
//        cellSignalCard.setSignal(0, true);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem privacyPolicyItem = menu.findItem(R.id.privacy_policy);
        privacyPolicyItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url)));
                startActivity(browserIntent);
                return true;
            }
        });

        MenuItem autoOffItem = menu.findItem(R.id.autoOff);
        autoOffItem.setChecked(AutoOff.DEFAULT_ENABLED);
        autoOff.setIsEnabled(autoOffItem.isChecked());
        autoOffItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                item.setChecked(!item.isChecked());
                autoOff.setIsEnabled(item.isChecked());
                return true;
            }
        });
        return true;
    }

    @Override
    protected void onDestroy() {
        MixpanelAPI.getInstance(getApplicationContext(), EventLogger.MIXPANEL_TOKEN).flush();
        super.onDestroy();
    }

    @Override
    public void onDataReceived(IJsonObject iJsonObject, IJsonObject iJsonObject2) {

    }

    @Override
    public void onDataCompleted(IJsonObject probeConfig, JsonElement checkpoint) {
        Log.i("MainActivity", "onDataCompleted");

        // Re-register to keep listening after probe completes.
        wifiProbe.registerPassiveListener(this);
        cellSignalProbe.registerPassiveListener(this);
    }
}
