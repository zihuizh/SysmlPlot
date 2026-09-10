package StructureModel {
    private import ScalarValues::*;

    part def Vehicle;
    part def Axle;
    part def Wheel;

    // 特化：Car :> Vehicle
    part def Car :> Vehicle {
        part axle : Axle;
    }

    // 重定义：SportsCar 重定义 Car 的 axle
    part def SportsCar :> Car {
        part sportsAxle : Axle redefines axle;
    }

    // 类型 + 子集化
    part car : Car {
        part wheels : Wheel[4];
        part frontLeft : Wheel subsets wheels;
    }
}

