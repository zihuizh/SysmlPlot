package TraceModel {
    private import ScalarValues::*;
    private import RequirementDerivation::*;

    requirement def SpeedLimit {
        attribute maxSpeed : Real;
    }

    // 整车速度需求：被满足、被验证
    requirement <'2.1'> vehicleSpeedReq : SpeedLimit {
        attribute :>> maxSpeed = 180;
    }

    // 由整车需求派生的子需求：同样被满足、被验证，所以整份模型没有覆盖率缺口
    requirement <'2.2'> motorSpeedReq : SpeedLimit {
        attribute :>> maxSpeed = 120;
    }

    #derivation connection {
        end #original ::> vehicleSpeedReq;
        end #derive ::> motorSpeedReq;
    }

    part vehicle {
        satisfy vehicleSpeedReq;
    }

    part motor {
        satisfy motorSpeedReq;
    }

    verification def SpeedTest {
        objective {
            verify vehicleSpeedReq;
            verify motorSpeedReq;
        }
    }

    verification speedTest : SpeedTest;
}
