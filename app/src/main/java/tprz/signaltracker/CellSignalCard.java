package tprz.signaltracker;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.splunk.mint.Mint;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

import it.gmariotti.cardslib.library.internal.Card;
import it.gmariotti.cardslib.library.internal.CardThumbnail;
import tprz.signaltracker.location.LocationProvider;
import tprz.signaltracker.location.Station;

/**
 * This class is a specific Card that is used to display updating information
 * about the phone's current cell signal strength. It updates a thumbnail and text
 * displaying information about the current connection state.
 */
public class CellSignalCard extends Card {
    private final DataReporter dataReporter;
    private final TelephonyManager telephonyManager;
    private final Handler handler;
    private final SignalRunnable signalPoller;
    private LocationProvider locationProvider;
    CellSignalListener cellSignalListener;
    private TextView cellSignalTextView;
    private LineChart chart;
    private int[] cellSignalDrawables = new int[] {
            R.drawable.ic_signal_cellular_0_bar_grey600_48dp,
            R.drawable.ic_signal_cellular_1_bar_grey600_48dp,
            R.drawable.ic_signal_cellular_2_bar_grey600_48dp,
            R.drawable.ic_signal_cellular_3_bar_grey600_48dp,
            R.drawable.ic_signal_cellular_4_bar_grey600_48dp,
            R.drawable.ic_signal_cellular_null_grey600_48dp
    };
    private boolean chartSetup = false;
    private boolean lock = false;
    private ImageView imageView;
    private boolean isEnabled;
    private int lastReading;
    private String lastReadingType = "Unknown";

    public CellSignalCard(Context context, int innerLayout, LocationProvider locationProvider, boolean isEnabled) {
        super(context, innerLayout);

        telephonyManager = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        this.cellSignalListener = new CellSignalListener();
        this.locationProvider = locationProvider;
        this.dataReporter = DataReporter.getInstance(context);
        this.handler = new Handler();
        this.signalPoller = new SignalRunnable();
        CardThumbnail thumbnail = new CardThumbnail(getContext());
        thumbnail.setDrawableResource(cellSignalDrawables[5]);
        addCardThumbnail(thumbnail);
        if(isEnabled) {
            enableCard();
        } else {
            disableCard();
        }
    }

    /**
     * Updates the card view to display the relevant thumbnail for the given
     * signal strength.
     * @param signalStrength The SignalStrength in ASU
     * @param networkType True if the phone is on a GSM network, false if it is on CDMA
     */
    public void setSignal(int signalStrength, String networkType) {
        String contentsText = String.format(Locale.ENGLISH, "%s\nASU: %d", networkType.isEmpty()
                ? "GSM" : networkType, signalStrength);
        if(cellSignalTextView == null) {
            return;
        }
        cellSignalTextView.setText(contentsText);

        int iconLevel;
        if(signalStrength == 0 || signalStrength == 99) {
            iconLevel = 0;
        } else if(signalStrength >= 12) {
            iconLevel = 4;
        } else if(signalStrength >= 8) {
            iconLevel = 3;
        } else if(signalStrength >= 5) {
            iconLevel = 2;
        } else {
            iconLevel = 1;
        }

        CardThumbnail thumbnail = new CardThumbnail(getContext());
        thumbnail.setDrawableResource(cellSignalDrawables[iconLevel]);
        addCardThumbnail(thumbnail);

        if(imageView != null) {
            imageView.setImageDrawable(getContext().getResources().getDrawable(cellSignalDrawables[iconLevel]));
        }

        notifyDataSetChanged();

        // Charting
        updateChart(signalStrength);
    }

    @Override
    public void setupInnerViewElements(ViewGroup parent, View view) {
        super.setupInnerViewElements(parent, view);
        cellSignalTextView = (TextView) parent.findViewById(R.id.cell_signal_text);
        this.chart = (LineChart) parent.findViewById(R.id.chart);
        this.imageView = (ImageView) parent.findViewById(R.id.imageView2);

        if(!this.chartSetup) {
            LineData data = new LineData();
            data.setValueTextColor(Color.WHITE);

            // add empty data
            chart.setData(data);

            YAxis yaxis = chart.getAxisLeft();
            yaxis.setAxisMinValue(0);
            yaxis.setAxisMaxValue(33);
            yaxis.setDrawGridLines(false);

            LimitLine minGood = new LimitLine(8);
            yaxis.addLimitLine(minGood);

            YAxis rightAxis = chart.getAxisRight();
            rightAxis.setEnabled(false);

            chart.setDescription("");

            chartSetup = true;
        }
    }

    /***
     * Updates the chart with a new signal strength reading
     *
     * @param signalStrength The signal strength in ASU
     */
    private void updateChart(int signalStrength) {
        if(this.lock) {
            return;
        }
        lock = true;
        LineData data = chart.getData();
        LineDataSet set = data.getDataSetByIndex(0);
        if (set == null) {
            set = createSet();
            data.addDataSet(set);
            set.setColor(getContext().getResources().getColor(R.color.accent));
        }

        data.addXValue("" + set.getEntryCount());
        Entry e = new Entry(signalStrength, set.getEntryCount());
        e.setXIndex(set.getEntryCount() + 1);
        data.addEntry(e, 0);
        set.setDrawCircles(false);

        chart.notifyDataSetChanged();

        // move to the latest entry
        chart.fitScreen();
        chart.invalidate();
        lock = false;
    }

    private LineDataSet createSet() {

        LineDataSet set = new LineDataSet(null, "ASU");
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(ColorTemplate.getHoloBlue());
        set.setCircleColor(Color.WHITE);
        set.setLineWidth(2f);
        set.setCircleSize(4f);
        set.setFillAlpha(65);
        set.setFillColor(ColorTemplate.getHoloBlue());
        set.setHighLightColor(Color.rgb(244, 117, 117));
        set.setValueTextColor(Color.WHITE);
        set.setValueTextSize(9f);
        set.setDrawValues(false);
        return set;
    }

    public void enableCard() {
        this.isEnabled = true;
        telephonyManager.listen(cellSignalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        handler.post(signalPoller);
    }

    public void disableCard() {
        this.isEnabled = false;
        telephonyManager.listen(cellSignalListener, PhoneStateListener.LISTEN_NONE);
        if(cellSignalTextView != null) {
            cellSignalTextView.setText(R.string.OffButtonText);
        }
        CardThumbnail thumbnail = new CardThumbnail(getContext());
        thumbnail.setDrawableResource(cellSignalDrawables[5]);
        addCardThumbnail(thumbnail);
        if(chartSetup) {
            notifyDataSetChanged();
        }
        lastReading = -1;
    }

    public class CellSignalListener extends PhoneStateListener {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        public boolean isConnectedMobile(Context context){
            NetworkInfo info = cm.getActiveNetworkInfo();
            return (info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_MOBILE);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength){
            int signalStrengthInt = signalStrength.getGsmSignalStrength() == 99 ? 0 : signalStrength.getGsmSignalStrength();
            Log.i("SigStrengthChange", "sig: " +  signalStrengthInt);
            lastReading = signalStrengthInt;
            if(cm.getActiveNetworkInfo() != null) {
                lastReadingType = cm.getActiveNetworkInfo().getSubtypeName();
            } else {
                lastReadingType = "";
            }

            try {
                Method m = SignalStrength.class.getMethod("getLteSignalStrength");
                Integer lteSignalStrength = (Integer) m.invoke(signalStrength);
                if(lteSignalStrength != 0 && lteSignalStrength != 99) {
                    // Either 0 or 99 and we may have no signal or no LTE signal.
                    // If we have no signal then the lastReading should be 0 from above anyway.
                    lastReading = lteSignalStrength;
                }
            } catch (Exception e) {
                // We use a catch all because who knows what might happen with the hidden API and
                // this is a fall back option on signal strength anyway.
                e.printStackTrace();
                Mint.logException(e);
            }

            if(signalStrengthInt != 0 || lastReading == 0) {
                MultiLogger.log(TAG, String.format(Locale.ENGLISH, "(signalStrength, %d)", signalStrength.getGsmSignalStrength()));
            }

        }
    }

    private class SignalRunnable implements Runnable {

        private static final long INTERVAL = 5 * 1000;

        @Override
        public void run() {
            Station currStation = locationProvider.getCurrentStation();
            if (currStation != null && lastReading != -1) {
                dataReporter.addSignalReading(currStation.getName(), telephonyManager.getNetworkOperatorName(), telephonyManager.getNetworkOperator(),
                        telephonyManager.getSimOperator(), telephonyManager.getSimOperatorName(), lastReading, lastReadingType);
            }

            setSignal(lastReading, lastReadingType);

            if(isEnabled) {
                handler.postDelayed(this, INTERVAL);
            }
        }
    }

}
