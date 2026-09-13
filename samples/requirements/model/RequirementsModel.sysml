package RequirementsModel {
    private import ScalarValues::*;
    private import RequirementDerivation::*;

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

    // 派生需求：底盘质量需求由整车质量需求派生而来
    requirement <'1.2'> chassisMassReq : MassLimit {
        attribute :>> massLimit = 900;
    }

    #derivation connection {
        end #original ::> massLimitReq;
        end #derive ::> chassisMassReq;
    }
}
