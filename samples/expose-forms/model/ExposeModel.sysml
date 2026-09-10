package ExposeModel {
    private import ScalarValues::*;

    part def Vehicle {
        attribute mass : Real;
    }

    part def Engine;
    part def Wheel;

    part vehicle : Vehicle {
        part engine : Engine;
        part wheels : Wheel[4];
    }
}
