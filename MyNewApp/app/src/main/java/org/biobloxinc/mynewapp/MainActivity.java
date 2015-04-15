package org.biobloxinc.mynewapp;

/*
Purpose: App to count number of steps taken
Assumptions: User is walking at a MODERATE pace, holding the tablet/phone perpendicular to
        their body, where the tablet face is on a plane parallel to the ground.
        The app will indicate if the planarity assumption is violated by displaying an icon
                with bidirectional arrows on it. If the arrows display right left, this implies
                the tablet is tilting in that direction and should be righted in order to count
                steps appropriately
                Likewise if the arrows display up down, this implies the tablet is tilting in
                the direction shown.
        It's all a little clunky, and I realize that at the very end of a series of steps,
                as the user is counter-acting the walking momentum in order to balance standing,
                the app typically identifies an additional step.
        Also, there must be some memory issue because a lag develops over time as the app is used.
 */

import android.app.Activity;
import android.bluetooth.BluetoothClass;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Timer;

public class MainActivity extends ActionBarActivity implements SensorEventListener{

    public static final String LOG_TAG = "PEDOMETER";

    // arrays for signal storage, both raw and conditioned data.
    private float[] mLastX = new float[]{0,0,0,0,0,0,0};
    private float[] mLastY = new float[]{0,0,0,0,0,0,0};
    private float[] mLastZ = new float[]{0,0,0,0,0,0,0};
    private float[] mLastEuc = new float[]{0,0,0,0,0,0,0};
    private float[] mLoGData = new float[]{0,0,0,0,0,0,0};
    // convolution vector for Laplacian of Gaussian. Used a zero-mean gaussian for this one.
    private float[] mLoGConvMatrix = new float[]{(float)0.005,(float)0.05,(float)0.245,
            (float)-0.6,(float)0.245,(float)0.05,(float)0.005};

    //set this number to the array sizes above. This is the size of the moving
    // window and convolution vector.
    private int maxIndex=7;
    //this is a totally empirically derived threshold. It's actually overly sensitive by a bit.
    private int mConvThresh = 180;
    // global step counter. Will be reset onRestart and pause of the app.
    private int numSteps=0;
    // let the app 'rest' for window size number of measurements in order to
    // remove artifacts of a step that was already detected.
    private int mNearStepThresh = maxIndex;
    private int mWithinLastTaps = mNearStepThresh;

    // indices to help with doing all the computation in a circular vector
    // (so we don't need to keep adding to heap space)
    private int mArrayInd = 0;
    private int mLocalDataInd = 0;
    private int mLocalConvInd = 0;

    private boolean mInitialized;
    private SensorManager mSensorManager;

    private Sensor mAccelerometerSensor;
    private Timer timer;
    private final float NOISE = (float) 10.0;
    private final float mAmplify = (float) 10.0;
    /** Called when the activity is first created. */


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //startService();
        //startDate();
        //setContentView(R.layout.activity_main);
        mInitialized = false;
        mArrayInd =0;
        numSteps=0;
        mWithinLastTaps = mNearStepThresh;

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mAccelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);

        Log.v(LOG_TAG, "Pedometer Service Started");
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // this will cause data gathering to start over
        mArrayInd = 0;
        mWithinLastTaps = mNearStepThresh;
        mInitialized = false;
        mSensorManager.registerListener(this, mAccelerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
        // restart count when app is paused
        numSteps=0;
        mWithinLastTaps = mNearStepThresh;
        mInitialized = false;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        TextView tvX= (TextView)findViewById(R.id.x_string);
        TextView tvY= (TextView)findViewById(R.id.y_string);
        TextView tvZ= (TextView)findViewById(R.id.z_string);
        ImageView iv = (ImageView)findViewById(R.id.genDirection);
        float x = event.values[0]*mAmplify;
        float y = event.values[1]*mAmplify;
        float z = event.values[2]*mAmplify;
        // the values are projections of the proper accelerated motion along two
        // perpendicular sensitivity axes.
        // For simplicity, calculate the square of magnitude of the acceleration vector
        // and preserve the sign along y axis.
        // This means that while we try to remove sensitivity to variations in device positioning,
        // we are really assuming that user is holding phone where y-axis is perpendicular
        // to the walking surface.
        // display the icon just to warn the user that they have changed the assumed orientation
        // of the device against the assumptions of the app and app is likely to overcount steps
        // at that point.
        int prevInd = 0;
        if((mArrayInd-1)<0){prevInd = (maxIndex - 1); }else{ prevInd = (mArrayInd-1); }
        float deltaX = Math.abs(mLastX[prevInd] - x);
        float deltaY = Math.abs(mLastY[prevInd] - y);
        float deltaZ = Math.abs(mLastZ[prevInd] - z);
        //there are actually no z signals in the cheapo tablet I'm using, but left it in anyhow.
        if (deltaX < NOISE) deltaX = (float)0.0;
        if (deltaY < NOISE) deltaY = (float)0.0;
        if (deltaZ < NOISE) deltaZ = (float)0.0;

        mLastX[mArrayInd] = x;
        mLastY[mArrayInd] = y;
        mLastZ[mArrayInd] = z;
        // don't want to waste compute time on sqrt
        float newVal = ((x*x) + (y*y))* Math.signum(y);
        float deltaEuc = Math.abs(mLastEuc[prevInd] - newVal);
        mLastEuc[mArrayInd]= newVal;
        mArrayInd++;
        if(mArrayInd==(maxIndex)) {
            mInitialized = true;
            mArrayInd = 0;
        }
        if (!mInitialized) {
            tvX.setText(""); //these were UI text elements left available for debugging
            tvY.setText("");
            tvZ.setText("0.0");
        } else {
            conditionSignal();
            int aStep = detectZeroCrossings();
            // a heuristic not to double-detect the same step.
            // giving the app a 'rest' from detecting for n number of data points
            // where n is the convolution window size for now.
            if(deltaEuc<mConvThresh && aStep==1){
                mWithinLastTaps = mNearStepThresh;;
                aStep=0;
            }
            Log.v(LOG_TAG, " "+ Float.toString(deltaX)+ "," +Float.toString(deltaY)+ "," + Float.toString(newVal) + "," + Float.toString(mLoGData[mArrayInd]) + ","+ Float.toString(deltaEuc) + ","+ Integer.toString(aStep) );
            numSteps += aStep;
            tvZ.setText(Integer.toString(numSteps));
            iv.setVisibility(View.VISIBLE);

            if (deltaY > deltaX) {
                iv.setImageResource(R.drawable.orbiconarrowsleftright1);
            } else if (deltaX > deltaY) {
                iv.setImageResource(R.drawable.orbiconarrowsupdown1);
            } else {
                iv.setVisibility(View.INVISIBLE);
            }
        }
    }

    private boolean conditionSignal(){
        // convolve the circular Euclidian acceleration vector by Laplacian of Gaussian
        // the current Euclidian data vector ends at value mArrayInd-1 (where 0th index means
        // last added value was at (maxIndex-1))

        float lSumVector = 0;
        mLocalDataInd = mArrayInd;
        mLocalConvInd = 0;
        // convolution
        while (mLocalConvInd<maxIndex) {
            lSumVector = lSumVector + (mLoGConvMatrix[mLocalConvInd] * mLastEuc[mLocalDataInd]);
            mLocalDataInd++;
            if(mLocalDataInd==maxIndex){ mLocalDataInd = 0;}
            mLocalConvInd++;
        }
        mLoGData[mArrayInd] = lSumVector;
        //Log.v(LOG_TAG, " "+ Integer.toString(mArrayInd)+ "," +Float.toString(mLoGData[mArrayInd]));
        return (true);
    }

    private int detectZeroCrossings(){
        int prevInd = mArrayInd-1;
        if(prevInd<0){prevInd = maxIndex-1;}
        // by going one more back with pPrevInd, we're essentially doing zero crossing
        // using the vector {-1/2, 0 , 1/2}
        // eventually decided that {-1/2,1/2} was more robust when the sigmoid on the gaussian
        // in the LoG is adequately large.
        int pPrevInd = prevInd-1;
        if(pPrevInd<0){pPrevInd = maxIndex-1;}

        // the function below will return 1 if the sign of the LoG had changed between the last 2
        // LoG calculations. Otherwise, it will return 0.
        if(mWithinLastTaps < mNearStepThresh){
            mWithinLastTaps++;
            return(0);
        }
        if ((Math.signum(mLoGData[mArrayInd])*Math.signum(mLoGData[prevInd]))==-1) {
            //Log.v(LOG_TAG, " "+ Float.toString(mLoGData[mArrayInd])+ "," +Float.toString(mLoGData[prevInd]) );
            mWithinLastTaps = 0;
            return (1);
        }
        return (0);

    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


}
