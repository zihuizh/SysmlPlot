package VehicleModel {
    private import ScalarValues::*;

    part def Vehicle;
    part def Engine;
    part def Transmission;

    part vehicle : Vehicle {
        attribute mass : Real = 1500;
        part engine : Engine {
            attribute power : Real;
        }
        part transmission : Transmission {
            attribute gearCount : Integer;
        }
    }
}
