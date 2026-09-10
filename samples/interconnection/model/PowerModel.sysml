package PowerModel {
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

    part powerSystem {
        part tank : Tank;
        part engine : Engine;
        connect tank.outlet to engine.inlet;
    }
}

