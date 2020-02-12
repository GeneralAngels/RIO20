package frc.robot.base.control.path;

import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.geometry.Translation2d;
import edu.wpi.first.wpilibj.trajectory.Trajectory;
import edu.wpi.first.wpilibj.trajectory.TrajectoryConfig;
import edu.wpi.first.wpilibj.trajectory.TrajectoryGenerator;
import frc.robot.base.drive.DifferentialDrive;
import frc.robot.base.drive.Gyroscope;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PathManager extends frc.robot.base.Module {

    private static final double TOLERANCE = 0.05;

    private DifferentialDrive drive;

    private Trajectory trajectory;

    private int index = 0;

    private double maxV = 1.5;
    private double maxA = 1.5;

    public double Kv = 3;
    public double Ktheta = 1.3 * maxV; // Note that low values (for example 0.33) means it should get to the setpoint in ~3 seconds! // 1.6*MAX_V
    public double Kcurv = 1 * maxV; //0.45*MAX_V
    public double Komega = 0;
    public boolean check = true;
    public boolean finalDirectionSwitch = true;
    public double desiredOmega;
    public double desiredVelocity;
    public double desiredVelocityPrev = 0;
    public double acc = 0;

    public PathManager(DifferentialDrive drive) {
        super("follower");
        this.drive = drive;
        this.createPath(new ArrayList<>());
    }

    public void follow() {
//        drive.direct(); TODO
        drive.updateOdometry();
    }

    public void trajectoryFollow() {
        // Setup previous values
        desiredVelocityPrev = desiredVelocity;

        Trajectory.State startPoint = trajectory.getStates().get(1);
        Trajectory.State endPoint = trajectory.getStates().get(trajectory.getStates().size() - 1);
        if (check) {
            maxV = 0.75 / Math.abs(Math.atan2(endPoint.poseMeters.getTranslation().getY() - startPoint.poseMeters.getTranslation().getY(), endPoint.poseMeters.getTranslation().getX() - startPoint.poseMeters.getTranslation().getX()));
            if (maxV > 1.5)
                maxV = 1.5;
            maxA = 0.5 / Math.abs(Math.atan2(endPoint.poseMeters.getTranslation().getY() - startPoint.poseMeters.getTranslation().getY(), endPoint.poseMeters.getTranslation().getX() - startPoint.poseMeters.getTranslation().getX()));
            if (maxA > 1.5)
                maxA = 1.5;
            check = false;
        }
        double omega = Gyroscope.getAngularVelocity();
        Trajectory.State currentGoal = trajectory.getStates().get(index);
        double curvature = currentGoal.curvatureRadPerMeter;
        if (index < trajectory.getStates().size() - 2) {
            Trajectory.State curvatureGoal = trajectory.getStates().get(index + 2);
            curvature = curvatureGoal.curvatureRadPerMeter;
        }
        curvature = Math.abs(curvature);
        set("curvature", "" + curvature);
        double[] errors = calculateErrors(currentGoal);
        if (index < trajectory.getStates().size() - 1) { //length of the trajectory
            desiredVelocity = maxV - curvature * Kcurv;
            acc = maxA - (movingAverageCurvature(trajectory.getStates()) / 2.0);
            log("acc: " + acc);
            desiredVelocity = Math.min(desiredVelocity, desiredVelocityPrev + (acc * 0.02));
            if ((desiredVelocity >= 0 && desiredVelocity < 0.5) || (maxV < (curvature * Kcurv))) {
                desiredVelocity = 0.5;
            }
            desiredOmega = errors[2] * Ktheta - omega * Komega;
            log("not last point");
            if (errors[0] < errors[1]) {
                ++index;
            }
        } else {
            toReverse(getPose(), trajectory.getStates().get(trajectory.getStates().size() - 1).poseMeters);
            desiredVelocity = errors[0] * Kv;
            desiredOmega = (currentGoal.poseMeters.getRotation().getRadians() - Math.toRadians(Gyroscope.getAngle())) * Ktheta - omega * Komega;
            // Check if done
            if (!isDone(getPose(), trajectory.getStates().get(trajectory.getStates().size() - 1).poseMeters)) {
                if (errors[0] > TOLERANCE) {
                    desiredOmega *= 1.3;
                    log("close");
                } else {
                    desiredVelocity = 0;
                    desiredOmega *= 1.8;
                    log("almost");
                }
                set("last point: ", "true");
            } else {
                desiredVelocity = 0;
                desiredOmega = 0;
                log("done");
            }
        }
        if (index < trajectory.getStates().size() - 1 && errors[0] < errors[1]) {
            ++index;
        }
        drive.driveVector(desiredVelocity, desiredOmega);
    }

    public double[] calculateErrors(Trajectory.State currentGoal) {
        // Previous error
        this.lastErrors = currentErrors;
        // Assign values
        double errorX = currentGoal.poseMeters.getTranslation().getX() - x;
        double errorY = currentGoal.poseMeters.getTranslation().getY() - y;
        // Theta calculation
        double errorTheta = (Math.atan2(errorY, errorX) - Math.toRadians(theta)) % (2 * Math.PI);
        // Return tuple
        return currentErrors = new double[]{Math.sqrt(Math.pow(errorX, 2) + Math.pow(errorY, 2)), lastErrors[1], errorTheta};
    }

    public Pose2d getPose() {
        return new Pose2d(x, y, Rotation2d.fromDegrees(theta));
    }

    public boolean isDone(Pose2d current, Pose2d end) {
        // Assign values
        double errorX = current.getTranslation().getX() - end.getTranslation().getX();
        double errorY = current.getTranslation().getY() - end.getTranslation().getY();
        // Calculate errors
        double errorTheta = Math.abs(current.getRotation().getDegrees() - end.getRotation().getDegrees());
        double errorPosition = errorX * Math.cos(Math.toRadians(theta)) + errorY * Math.sin(Math.toRadians(theta));
        // Check against tolerances
        return Math.abs(errorPosition) < TOLERANCE && errorTheta < DEGREE_TOLERANCE;
    }

    public void toReverse(Pose2d current, Pose2d end) {
        // Assign values
        double errorX = current.getTranslation().getX() - end.getTranslation().getX();
        double errorY = current.getTranslation().getY() - end.getTranslation().getY();
        // Calculate errors
        double errorTheta = Math.abs(current.getRotation().getDegrees() - end.getRotation().getDegrees());
        double errorPosition = errorX * Math.cos(Math.toRadians(theta)) + errorY * Math.sin(Math.toRadians(theta));
        // Black magic
        if ((errorPosition > 0 && Kv > 0) || (errorPosition < 0 && Kv < 0)) {
            if (finalDirectionSwitch) {
                Kv *= -1;
                finalDirectionSwitch = false;
            }
        } else
            finalDirectionSwitch = true;
    }

    public double[][] getPoints(double[] a, double[] b, int amount) {//coefx, coefy, amount of point
        double[][] points = new double[3][amount];

        for (int i = 0; i < amount; i++) {
            points[i][0] = put(a, ((double) i) / amount);
            points[i][1] = put(b, ((double) i) / amount);
            //points[i][2] = Math.atan(put())
        }
        return points;
    }

    public double put(double[] a, double val) {
        double ret = 0;
        for (int i = 0; i < a.length; i++) {
            ret += a[i] * Math.pow(val, a.length - i - 1);
        }
        return ret;
    }

    public void createPath(ArrayList<Translation2d> waypoints) {

        Pose2d startPoint = new Pose2d(0, 0, Rotation2d.fromDegrees(0));
        Pose2d endPoint = new Pose2d(2, 0.5, Rotation2d.fromDegrees(0));
        //interiorWaypoints.add(new Translation2d(1, 0));
//        interiorWaypoints.add(new Translation2d(1.0, 0.5));

        TrajectoryConfig config = new TrajectoryConfig(4, 1);
        config.setEndVelocity(0);
        //config.setReversed(true);
        trajectory = TrajectoryGenerator.generateTrajectory(startPoint, waypoints, endPoint, config);
        trajectory.getStates();
    }

    public double movingAverageCurvature(List<Trajectory.State> trajectory) {
        int size = trajectory.size();
        double average = (Math.abs(trajectory.get(size - 1).curvatureRadPerMeter) + Math.abs(trajectory.get(size - 2).curvatureRadPerMeter)) / 2;
        for (int i = size - 3; i >= 0; --i) {
            average = (average + Math.abs(trajectory.get(i).curvatureRadPerMeter)) / 2;
        }
        return average;
    }

    public double[] derivePolynomial(double[] coefficients) {
        double[] result = new double[coefficients.length];
        result[0] = 0;
        for (int i = 1; i < coefficients.length; i++) {
            result[i] = coefficients[i - 1] * i;
        }
        return result;
    }

    public Trajectory getTrajectory() {
        return trajectory;
    }
}
