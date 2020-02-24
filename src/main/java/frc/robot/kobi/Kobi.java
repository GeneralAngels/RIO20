package frc.robot.kobi;

import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;
import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj.Joystick;
import edu.wpi.first.wpilibj.PowerDistributionPanel;
import edu.wpi.first.wpilibj.XboxController;
import frc.robot.base.Bot;
import frc.robot.base.control.path.PathManager;
import frc.robot.base.rgb.RGB;
import frc.robot.base.utils.General;
import frc.robot.base.utils.Toggle;
import frc.robot.base.utils.auto.Autonomous;
import frc.robot.kobi.systems.KobiDrive;
import frc.robot.kobi.systems.KobiFeeder;
import frc.robot.kobi.systems.KobiShooter;
import org.opencv.core.Mat;

import java.awt.*;

public class Kobi extends Bot {

    /**
     * Pinout as of 16/02/2020
     * DIO:
     * 0, 1 - Left Encoder
     * 2, 3 - Right Encoder
     * 4, 5 - Min/Max LimS/W
     * 6, 7 - Turret LimS/W
     * <p>
     * PWM:
     * 0, 1 - Left Victors
     * 2, 3 - Right Victors
     * 4 - Hood Servo
     * <p>
     * AIO:
     * 0 - Hood Potentiometer
     * <p>
     * CAN:
     * Shooter 1,2,3 - 20, 21, 22
     * Turret - 19
     * Feeder - 18
     * Roller - 17
     * 50, 51, ~52~ - Left Sparks
     * 53, 54, ~55~ - Right Sparks
     * <p>
     * Talon Encoders:
     * Slide - has encoder
     * Turret - has encoder
     * Shooter - has encoder
     */

    private static final double DEADBAND = 0.05;

    // Joystick
    private Joystick driverLeft;
    private Joystick driverRight;
    private XboxController operator;

    // Modules
    private KobiDrive drive;
    private KobiFeeder feeder;
    private KobiShooter shooter;
    private RGB rgb;

    private Autonomous autonomous;
    private PathManager manager;

    // PDP
    private static PowerDistributionPanel pdp = new PowerDistributionPanel(0);

    public Kobi() {
        // Joystick
        operator = new XboxController(0);
        driverLeft = new Joystick(1);
        driverRight = new Joystick(2);

        // Modules
        rgb = new RGB();
        drive = new KobiDrive();
        manager = new PathManager(drive);
        feeder = new KobiFeeder();
        shooter = new KobiShooter();
        autonomous = new Autonomous(this);
        // Ah yes, enslaved modules
        enslave(autonomous);
        enslave(manager);
        enslave(shooter);
        enslave(feeder);
        enslave(drive);
        enslave(rgb);
        // Resets
        drive.updateOdometry();
        // RGB mode
        rgb.setMode(RGB.Mode.Fill);
    }

    @Override
    public void autonomous() {
        // Voltage
        drive.updateVoltage(pdp.getVoltage());

        // Time
        set("time", String.valueOf(millis()));

        // Update odometry
        drive.updateOdometry();

        // Auto
        autonomous.loop();
    }

    @Override
    public void teleop() {
        // Time
        set("time", String.valueOf(millis()));

        // Voltage
        drive.updateVoltage(pdp.getVoltage());

        // Testing

        feeder.limitSwitchTest();

        // Shit

        // Production
        handleControllers();
        //drive.driveManual(-operator.getY(GenericHID.Hand.kLeft) / 2, operator.getX(GenericHID.Hand.kLeft) / 2);

        // Update shooter positions
        shooter.updatePositions();
    }

    private void handleControllers() {
        // Setpoint lock
        shooter.setSetPointLock(operator.getAButton());

        // Setpoints

        double shooterVelocity = 0;
        double turretVelocity = 0;
        double hoodPosition = KobiShooter.HOOD_SAFE_MAXIMUM_ANGLE;

        KobiFeeder.Direction feederDirection = KobiFeeder.Direction.Stop;
        KobiFeeder.Direction rollerDirection = KobiFeeder.Direction.Stop;
        KobiFeeder.Direction sliderDirection = KobiFeeder.Direction.Stop;

        // Flywheel
        if (!operator.getAButton()) {
            // Shooter velocity from controller
            shooterVelocity = General.deadband(-operator.getY(GenericHID.Hand.kRight), DEADBAND) * 30;
        } else {
            shooterVelocity = shooter.getShooterSetPoint();
        }
        // Check flywheel acceleration to initiate feeding
        boolean flywheelAccelerated = shooter.setShooterVelocity(shooterVelocity);
        // Make sure the input is not 0 and that we accelerated
        if (flywheelAccelerated && General.deadband(shooterVelocity, DEADBAND) != 0) {
            if (shooterVelocity > 0)
                feederDirection = KobiFeeder.Direction.In;
        }
        // Read feeder delta from operator
        double feederDeltaManual = General.deadband(operator.getTriggerAxis(GenericHID.Hand.kLeft), DEADBAND) - General.deadband(operator.getTriggerAxis(GenericHID.Hand.kRight), DEADBAND);
        // Check if the delta is not 0
        if (feederDeltaManual != 0) {
            feederDirection = fromJoystick(feederDeltaManual);
        }
        // Set feeder
        feeder.feed(feederDirection);

        // Hood
        if (!operator.getAButton()) {
            if (General.deadband(shooterVelocity, DEADBAND) != 0) {
                hoodPosition = KobiShooter.HOOD_SAFE_MINIMUM_ANGLE;
            }
        } else {
            if (shooter.getHoodSetPoint() < KobiShooter.HOOD_SAFE_MINIMUM_ANGLE) {
                hoodPosition = KobiShooter.HOOD_SAFE_MINIMUM_ANGLE;
            } else if (shooter.getHoodSetPoint() > KobiShooter.HOOD_SAFE_MAXIMUM_ANGLE) {
                hoodPosition = KobiShooter.HOOD_SAFE_MAXIMUM_ANGLE;
            } else {
                hoodPosition = shooter.getHoodSetPoint();
            }
        }
        // Set hood position
        shooter.setHoodPosition(hoodPosition);

        // Turret
        if (!operator.getXButton()) {
            // Manual control
            if (operator.getStartButton()) {
                turretVelocity += 1;
            }
            if (operator.getBackButton()) {
                turretVelocity -= 1;
            }
        } else {
            turretVelocity = shooter.getTurretSetPoint();
        }
        // Set turret
        shooter.setTurretVelocity(-turretVelocity / 5);

        // Move slider
        // Block other roller input
        if (operator.getPOV() == 0 || operator.getPOV() == 180) {
            rollerDirection = KobiFeeder.Direction.In;
            // Check slider direction
            if (operator.getPOV() == 0) {
                sliderDirection = KobiFeeder.Direction.Out;
            } else if (operator.getPOV() == 180) {
                sliderDirection = KobiFeeder.Direction.In;
            }
        }
        // Read other roller direction
        if (operator.getBumper(GenericHID.Hand.kRight)) {
            rollerDirection = KobiFeeder.Direction.In;
        } else if (operator.getBumper(GenericHID.Hand.kLeft)) {
            rollerDirection = KobiFeeder.Direction.Out;
        }
        // Slide & Roll
        feeder.roll(rollerDirection);
        feeder.slide(sliderDirection);
        // Drive
        drive.direct(-driverLeft.getY(), -driverRight.getY());
    }

    private KobiFeeder.Direction fromJoystick(double value) {
        if (Math.abs(value) < DEADBAND)
            return KobiFeeder.Direction.Stop;
        if (value < 0)
            return KobiFeeder.Direction.In;
        else
            return KobiFeeder.Direction.Out;
    }
}