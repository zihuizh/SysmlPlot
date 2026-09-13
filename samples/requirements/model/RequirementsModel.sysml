package RequirementsModel {
    private import ScalarValues::*;

    requirement def MassLimit {
        doc /* 车辆总质量不得超过限制 */
        attribute massActual : Real;
        attribute massLimit : Real;
        require constraint { massActual <= massLimit }
    }

    part def Vehicle;

    requirement <'1.1'> massLimitReq : MassLimit {
        doc /* 整车干质量不得超过 2000 kg。 */
        attribute :>> massLimit = 2000;
    }

    part vehicleDesign : Vehicle {
        attribute dryMass : Real = 1500;
        satisfy massLimitReq;
    }

    // 验证：objective 里 verify 需求
    verification def MassTest {
        subject testVehicle : Vehicle;
        objective {
            verify massLimitReq;
        }
    }

    verification massTest : MassTest;
}
