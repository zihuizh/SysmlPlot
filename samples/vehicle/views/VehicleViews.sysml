package VehicleViews {
    private import VehicleModel::*;
    private import Views::*;

    view def 'Part Structure View' {
        filter @SysML::PartUsage;
    }

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
}
