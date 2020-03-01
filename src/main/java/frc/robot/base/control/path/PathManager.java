package frc.robot.base.control.path;

import com.ga2230.shleam.advanced.frc.FRCModule;
import com.ga2230.shleam.base.structure.Function;
import com.ga2230.shleam.base.structure.Result;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.trajectory.Trajectory;
import edu.wpi.first.wpilibj.trajectory.TrajectoryConfig;
import edu.wpi.first.wpilibj.trajectory.TrajectoryGenerator;
import frc.robot.base.drive.DifferentialDrive;
import frc.robot.base.drive.Odometry;
import frc.robot.base.utils.General;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Mat;

import java.util.ArrayList;

public class PathManager extends FRCModule {

    private static final double TIME_DELTA = 0.02;

    private static final double DISTANCE_TOLERANCE = 0.05;
    private static final double RADIAN_TOLERANCE = 3 * Math.PI / 180;
    private static final double DEGREE_TOLERANCE = 5;

    private static final boolean LOGS_ENABLED = true;

    private static final int FUTURE_CURVATURE_STATES = 2; // How many states to skip when looking for future curvatures (for linear acceleration)

    private DifferentialDrive drive;

    private Trajectory trajectory;

    // Trajectory progress
    private int index = 0;

    private double theta, omega, x, y;
    private double minVelocity, maxVelocity, maxAcceleration;

    public double kTheta, kCurvature, kOmega, kVelocity;
    public double currentDesiredOmega, currentDesiredVelocity, previousDesiredVelocity;

    public PathManager(DifferentialDrive drive) {
        super("path");
        this.drive = drive;

        // Command registration for autonomous

        register("fetch", new Function() {
            @Override
            public Result execute(String parameter) throws Exception {
                if (trajectory != null) {
                    JSONArray array = new JSONArray();
                    for (Trajectory.State state : trajectory.getStates()) {
                        JSONObject object = new JSONObject();
                        object.put("x", state.poseMeters.getTranslation().getX());
                        object.put("y", state.poseMeters.getTranslation().getY());
                        object.put("angle", state.poseMeters.getRotation().getDegrees());
                        array.put(object);
                    }
                    return Result.finished(array.toString());
                }
                return Result.finished("[]");
            }
        });

        register("create", new Function() {
            @Override
            public Result execute(String parameter) throws Exception {
                // Parse string into three parameters (x, y, theta)
                String[] split = parameter.split(" ");
                if (split.length == 3) {
                    double x = Double.parseDouble(split[0]);
                    double y = Double.parseDouble(split[1]);
                    double theta = Double.parseDouble(split[2]);
                    createTrajectory(new Pose2d(x, y, Rotation2d.fromDegrees(theta)));
                    return Result.finished("Trajectory created");
                } else {
                    return Result.notFinished("Wrong number of parameters");
                }
            }
        });

        register("follow", new Function() {
            @Override
            public Result execute(String parameter) throws Exception {
                if (trajectory != null) {
                    updateProgress();
                    if (index >= trajectory.getStates().size()) {
                        return Result.finished("Done");
                    }
                    followTrajectory();
                    return Result.notFinished("Not done");
                } else {
                    return Result.notFinished("No trajectory");
                }
            }
        });
    }

    private void updateProgress() {
        set("index", String.valueOf(index));
        set("length", String.valueOf(trajectory.getStates().size()));
    }

    private void updateOdometry() {
        Odometry odometry = drive.updateOdometry();
//        theta = General.compassify(odometry.getTheta());
        theta = odometry.getTheta();
        omega = odometry.getOmega();
        x = odometry.getX();
        y = odometry.getY();
    }

    public void createTrajectory(Pose2d target) {
        // Reset index
        index = 1;
        // Reset things
        kOmega = 0.05;
        kVelocity = 2;
        maxVelocity = 2;
        maxAcceleration = 2;
        // Update odometry
        updateOdometry();
        // Configure trajectory
        TrajectoryConfig config = new TrajectoryConfig(2, 1);
        config.setEndVelocity(0);
        // Poses
        Pose2d start = getPose();
        Pose2d end = new Pose2d(target.getTranslation().getX(), target.getTranslation().getY(), Rotation2d.fromDegrees(target.getRotation().getDegrees()));
        // Calculate curvature
        double trajectoryCurvature = curvature(end, start);
        // Calculate the maximum acceleration and velocity
//        maxVelocity = Math.min(4 / Math.abs(trajectoryCurvature), 1.5);
        double angleDelta = Math.abs(Math.sin(Math.toRadians(end.getRotation().getDegrees() - theta)));
        double yDelta = Math.abs(end.getTranslation().getY() - y);
        // Calculate max acceleration
        if (yDelta > 0.4)
            maxAcceleration = Math.min((3 / (Math.abs(trajectoryCurvature)) + (angleDelta * yDelta)), maxAcceleration);
        else
            maxAcceleration = Math.min(3.5 / Math.abs(trajectoryCurvature), maxAcceleration);
        // Calculate minimum velocity
        minVelocity = 0.5;
        // Reset some more things
        kTheta = 3.5;
        kCurvature = 4;
        // Generate trajectory
        trajectory = TrajectoryGenerator.generateTrajectory(start, new ArrayList<>(), end, config);
    }

    public void createReversedTrajectory(Pose2d target) { //TODO: check reversed trajectory creation
        // Reset index
        index = 1;
        // Reset things
        kOmega = 0.03;
        kVelocity = -6;
        maxVelocity = -2.5;
        maxAcceleration = -2;
        // Update odometry
        updateOdometry();
        // Configure trajectory
        TrajectoryConfig config = new TrajectoryConfig(2, 1);
        config.setEndVelocity(0);
        // Poses
        Pose2d start = getPose();
        Pose2d end = new Pose2d(start.getTranslation().getX() + target.getTranslation().getX(), start.getTranslation().getY() + target.getTranslation().getY(), Rotation2d.fromDegrees(start.getRotation().getDegrees() + target.getRotation().getDegrees()));
        // Calculate curvature
        double trajectoryCurvature = curvature(end, start);
        // Calculate the maximum acceleration and velocity
        if (Math.abs(end.getTranslation().getY() - y) > 0.4) {
            maxAcceleration = Math.min(-((3 / (Math.abs(trajectoryCurvature))) + (Math.sin(Math.toRadians(end.getRotation().getDegrees() - theta)))), maxAcceleration);
        } else {
            maxAcceleration = Math.min(-(4.5 / Math.abs(trajectoryCurvature)), maxAcceleration);
        }
        // Reset some more things
        kTheta = 2.5;
        kCurvature = -3.5;
        // Generate trajectory
        trajectory = TrajectoryGenerator.generateTrajectory(start, new ArrayList<>(), end, config);
    }

    public void followTrajectory() {
        // Follow trajectory
        updateProgress();
        // Check if finished
        if (index < trajectory.getStates().size()) {
            // Setup previous values
            previousDesiredVelocity = currentDesiredVelocity;
            // Setup odometry value
            updateOdometry();
            // Setup states
            Trajectory.State current = trajectory.getStates().get(index);
            // Calculate curvature
            double curvature = current.curvatureRadPerMeter;
            if (index < trajectory.getStates().size() - FUTURE_CURVATURE_STATES) // Check future curvature
                curvature = trajectory.getStates().get(index + FUTURE_CURVATURE_STATES).curvatureRadPerMeter; // Look for future curvature
            // Absolute the values
            curvature = Math.abs(curvature);
            // Calculate new errors
            double[] errors = calculateErrors();
            // Is last point yet?
            boolean isLast = !(index < trajectory.getStates().size() - 1);
            // Calculate kTheta
            kTheta = Math.abs(kTheta);
            // Make sure we are not done yet
            if (!isLast) {
                // Calculate average curvature
                double acceleration = maxAcceleration - (movingAverageCurvature() / 2.0); // 2.0 is an arbitrary value
                // Sets
                set("maxV", String.valueOf(maxVelocity));
                set("maxA", String.valueOf(maxAcceleration));
                // Calculate velocity (maxVelocity - curvature * kCurv)(V = V0 + a*t)
                currentDesiredVelocity = Math.min(maxVelocity - (curvature * kCurvature), previousDesiredVelocity + (acceleration * TIME_DELTA));
                // Check range
                if ((currentDesiredVelocity >= 0 && currentDesiredVelocity < minVelocity) || (maxVelocity < (curvature * kCurvature))) // To make sure velocity isn't too low
                    currentDesiredVelocity = minVelocity;
                // Calculate omega (PD control)
                currentDesiredOmega = errors[2] * kTheta - omega * kOmega;
            } else {
                // Last point
                calculateVelocityCoefficient();
                // Calculate errors
                double[] lastErrors = calculateLastErrors();
                // Calculate desired sh*t
                currentDesiredVelocity = lastErrors[0] * kVelocity;
                currentDesiredOmega = lastErrors[2] * kTheta - omega * kOmega;
                // Check if done
                if (!(Math.abs(lastErrors[1]) < DISTANCE_TOLERANCE && Math.abs(lastErrors[2]) < RADIAN_TOLERANCE)) {
                    // Distance
                    if (Math.abs(lastErrors[1]) > DISTANCE_TOLERANCE) {
                        currentDesiredVelocity *= 1;
                    } else {
                        currentDesiredVelocity = 0;
                    }
                    if (Math.abs(lastErrors[2]) > RADIAN_TOLERANCE) {
                        currentDesiredOmega *= 3;
                    } else {
                        currentDesiredOmega = 0;
                    }
                } else {
                    currentDesiredVelocity = 0;
                    currentDesiredOmega = 0;
                }
                // Deadbands
                currentDesiredVelocity = General.deadband(currentDesiredVelocity, 0.1);
                currentDesiredOmega = General.deadband(currentDesiredOmega, 0.1);
                // Check if done (actually)
                if (currentDesiredVelocity == 0 && currentDesiredOmega == 0) {
                    this.index += 1;
                    if (this.index > trajectory.getStates().size())
                        this.index = trajectory.getStates().size();
                }
            }
            if (!isLast && errors[0] < errors[1]) {
                this.index++;
            }
            set("targetVelocity", String.valueOf(currentDesiredVelocity));
            set("targetOmega", String.valueOf(currentDesiredOmega));
            drive.driveVector(currentDesiredVelocity, currentDesiredOmega);
        }
    }

    public void followReversedTrajectory() { //TODO: check reversed following
        // Follow trajectory
        updateProgress();
        // Check if finished
        if (index < trajectory.getStates().size()) {
            // Setup previous values
            previousDesiredVelocity = currentDesiredVelocity;
            // Setup odometry value
            updateOdometry();
            // Setup states
            Trajectory.State current = trajectory.getStates().get(index);
            // Calculate curvature
            double curvature = current.curvatureRadPerMeter;
            if (index < trajectory.getStates().size() - FUTURE_CURVATURE_STATES) // Check future curvature
                curvature = trajectory.getStates().get(index + FUTURE_CURVATURE_STATES).curvatureRadPerMeter; // Look for future curvature
            // Absolute the values
            curvature = Math.abs(curvature);
            // Calculate new errors
            double[] errors = calculateErrors();
            // Is last point yet?
            boolean isLast = !(index < trajectory.getStates().size() - 1);
            // Calculate kTheta
            kTheta = Math.abs(kTheta);
            // Make sure we are not done yet
            if (!isLast) {
                // Calculate average curvature
                double acceleration = maxAcceleration + (movingAverageCurvature() / 2.0); // 2.0 is an arbitrary value
                // Sets
                set("maxV", String.valueOf(maxVelocity));
                set("maxA", String.valueOf(maxAcceleration));
                // Calculate velocity (maxVelocity - curvature * kCurv)(V = V0 + a*t)
                currentDesiredVelocity = Math.min(maxVelocity + (curvature * kCurvature), previousDesiredVelocity + (acceleration * TIME_DELTA));
                // Check range
                if ((currentDesiredVelocity <= 0 && currentDesiredVelocity < -minVelocity) || (maxVelocity > (curvature * kCurvature))) // To make sure velocity isn't too low
                    currentDesiredVelocity = -minVelocity;
                // Calculate omega (PD control)
                currentDesiredOmega = errors[2] * kTheta - omega * kOmega;
            } else {
                // Last point
                calculateVelocityCoefficient();
                // Calculate errors
                double[] lastErrors = calculateLastErrors();
                // Calculate desired sh*t
                currentDesiredVelocity = -lastErrors[0] * kVelocity;
                //currentDesiredOmega = (current.poseMeters.getRotation().getRadians() - Math.toRadians(Gyroscope.getAngle())) * kTheta - omega * kOmega;
                currentDesiredOmega = lastErrors[2] * kTheta - omega * kOmega;
                // Check if done
                if (!(Math.abs(lastErrors[1]) < DISTANCE_TOLERANCE && Math.abs(lastErrors[2]) < RADIAN_TOLERANCE)) {
                    // Distance
                    if (Math.abs(lastErrors[1]) > DISTANCE_TOLERANCE) {
                        currentDesiredVelocity *= 1;
                    } else {
                        currentDesiredVelocity = 0;
                    }
                    if (Math.abs(lastErrors[2]) > RADIAN_TOLERANCE) {
                        currentDesiredOmega *= 3.5;
                    } else {
                        currentDesiredOmega = 0;
                    }
                } else {
                    currentDesiredVelocity = 0;
                    currentDesiredOmega = 0;
                }
                // Deadbands
                currentDesiredVelocity = General.deadband(currentDesiredVelocity, 0.07);
                currentDesiredOmega = General.deadband(currentDesiredOmega, 0.07);
                // Check if done (actually)
                if (currentDesiredVelocity == 0 && currentDesiredOmega == 0) {
                    this.index++;
                }
            }
            if (!isLast && errors[0] < errors[1]) {
                this.index++;
            }
            set("targetVelocity", String.valueOf(currentDesiredVelocity));
            set("targetOmega", String.valueOf(currentDesiredOmega));
            drive.driveVector(currentDesiredVelocity, currentDesiredOmega);
        }
    }

    public double[] calculateErrors() {
        // Theta calculation
        double errorTheta = (curvature(getPose(), trajectory.getStates().get(index).poseMeters) - Math.toRadians(theta)) % (2 * Math.PI);
        // Error calculation
        double currentDistanceError = absoluteDistance(getPose(), trajectory.getStates().get(index).poseMeters);
        double previousDistanceError = absoluteDistance(getPose(), trajectory.getStates().get(index - 1).poseMeters);
        // Return tuple
        return new double[]{currentDistanceError, previousDistanceError, errorTheta};
    }

    public double[] calculateLastErrors() {
        // Get the errors
        Pose2d lastPose = trajectory.getStates().get(trajectory.getStates().size() - 1).poseMeters;
        // Get deltas
        double[] deltas = deltas(getPose(), lastPose);
        // Calculate errors
        double relativeErrorPosition = relativeDistance(getPose(), lastPose);
        double absoluteErrorPosition = absoluteDistance(getPose(), lastPose);
        // Calculate angle error
        double errorTheta = Math.toRadians(trajectory.getStates().get(trajectory.getStates().size() - 1).poseMeters.getRotation().getDegrees() - theta);
        // Return tuple
        return new double[]{absoluteErrorPosition, relativeErrorPosition, errorTheta};
    }

    public Pose2d getPose() {
        return new Pose2d(x, y, Rotation2d.fromDegrees(theta));
    }

    private double[] deltas(Pose2d first, Pose2d last) {
        // Calculate errors
        double errorX = last.getTranslation().getX() - first.getTranslation().getX();
        double errorY = last.getTranslation().getY() - first.getTranslation().getY();
        // Return array
        return new double[]{errorX, errorY};
    }

    private double curvature(Pose2d first, Pose2d last) {
        // Calculate deltas
        double[] deltas = deltas(first, last);
        // Calculate angle
        return Math.atan2(deltas[1], deltas[0]);
    }

    private double absoluteDistance(Pose2d first, Pose2d last) {
        // Assign values
        double[] errors = deltas(first, last);
        // Calculate and return
        return Math.sqrt(errors[0] * errors[0] + errors[1] * errors[1]);
    }

    private double relativeDistance(Pose2d first, Pose2d last) {
        // Assign values
        double[] errors = deltas(first, last);
        // Radian degrees of first (instead of theta)
        double angle = first.getRotation().getRadians();
        // Return distance
        return errors[0] * Math.cos(angle) + errors[1] * Math.sin(angle);
    }

    private void calculateVelocityCoefficient() {
        // Calculate errors
        double errorDistance = relativeDistance(getPose(), trajectory.getStates().get(index).poseMeters);
        kVelocity = Math.abs(kVelocity);
        if (errorDistance < 0) {
            kVelocity *= -1;
        } else {
            kVelocity *= 1;
        }
    }

    private double movingAverageCurvature() {
        double average = 0;
        for (int i = trajectory.getStates().size() - 1; i >= 0; i--) {
            average = (average + Math.abs(trajectory.getStates().get(i).curvatureRadPerMeter)) / 2;
        }
        return average;
    }

    @Override
    protected void log(String string) {
        if (LOGS_ENABLED)
            super.log(string);
    }

    private double[] derivePolynomial(double[] coefficients) {
        double[] result = new double[coefficients.length];
        result[0] = 0;
        for (int i = 1; i < coefficients.length; i++) {
            result[i] = coefficients[i - 1] * i;
        }
        return result;
    }
}
