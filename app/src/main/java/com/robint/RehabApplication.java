package com.robint;

import android.app.Application;
import com.robint.device.RobotScreenAngle;

public class RehabApplication extends Application {
    private RobotScreenAngle screenAngle;

    @Override public void onCreate() {
        super.onCreate();
        screenAngle = new RobotScreenAngle(getPackageName());
        screenAngle.apply(RobotScreenAngle.DEFAULT_PITCH);
    }

    public RobotScreenAngle screenAngle() { return screenAngle; }
}
