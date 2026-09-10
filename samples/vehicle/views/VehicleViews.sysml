package VehicleViews {
    private import VehicleModel::*;
    private import Views::*;

    view def 'Part Structure View' {
        filter @SysML::PartUsage;
    }

    // 不带 filter 的视图定义，用于对照"expose 决定候选集、filter 决定入图元素"
    view def 'Unfiltered View';

    // 递归暴露整个 vehicle 的语义范围
    view 'vehicle structure' : 'Part Structure View' {
        expose vehicle::**;
        render asTreeDiagram;
    }

    // 只暴露显式列出的元素，用于对照递归形式
    view 'vehicle structure (explicit)' : 'Part Structure View' {
        expose vehicle;
        expose vehicle::engine;
        expose vehicle::transmission;
    }

    // 与 'vehicle structure' 同样的 expose，但没有 filter：应能看到 attribute
    view 'vehicle structure (unfiltered)' : 'Unfiltered View' {
        expose vehicle::**;
    }
}
