package FlowsModel {
    item def Fuel;

    port def FuelOutPort {
        out item fuelSupply : Fuel;
    }

    port def FuelInPort {
        in item fuelSupply : Fuel;
    }

    part def Tank {
        port outlet : FuelOutPort;
    }

    part def Engine {
        port inlet : FuelInPort;
    }

    part def LogicalController;
    part def PhysicalBoard;

    part powerSystem {
        part tank : Tank;
        part engine : Engine;
        part logical : LogicalController;
        part board : PhysicalBoard;

        flow of Fuel from tank.outlet.fuelSupply to engine.inlet.fuelSupply;
        allocate logical to board;
    }
}

