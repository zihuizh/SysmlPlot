package RequirementsModel {
    private import ScalarValues::*;

    requirement def MassLimit {
        doc /* 车辆总质量不得超过限制 */
        attribute massActual : Real;
        attribute massLimit : Real;
        require constraint { massActual <= massLimit }
    }

    part def Vehicle;

    requirement massLimitReq : MassLimit {
        attribute :>> massLimit = 2000;
    }

    part vehicleDesign : Vehicle {
        attribute dryMass : Real = 1500;
        satisfy massLimitReq;
    }
}

