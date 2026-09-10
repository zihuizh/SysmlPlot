package StructureViews {
    private import StructureModel::*;

    view def 'Structure View';

    // 不带 filter：暴露范围内的一切，包括隐式元素
    view 'structure' : 'Structure View' {
        expose car::**;
        expose Car::**;
        expose SportsCar::**;
        expose Vehicle;
        expose Wheel;
        expose Axle;
    }

    // 只看 part 用法与定义，便于阅读
    view 'structure (parts)' : 'Structure View' {
        filter @SysML::PartUsage or @SysML::PartDefinition;
        expose car::**;
        expose Car::**;
        expose SportsCar::**;
        expose Vehicle;
        expose Wheel;
        expose Axle;
    }
}
