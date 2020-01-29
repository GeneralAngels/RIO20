package frc.robot.shuby;

import com.ctre.phoenix.motorcontrol.can.VictorSPX;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;
import edu.wpi.first.wpilibj.VictorSP;
import frc.robot.base.drive.DifferentialDrive;

public class ShubyDrive extends DifferentialDrive<VictorSP> {

    public ShubyDrive() {
        left.addMotor(new VictorSP(0));
        left.addMotor(new VictorSP(1));
        left.addMotor(new VictorSP(2));
        right.addMotor(new VictorSP(3));
        right.addMotor(new VictorSP(4));
        right.addMotor(new VictorSP(5));
    }

}
