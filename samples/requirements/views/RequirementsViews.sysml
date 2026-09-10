package RequirementsViews {
    private import RequirementsModel::*;
    private import StandardViewDefinitions::*;

    view 'requirement trace' : GeneralView {
        expose vehicleDesign;
        expose massLimitReq;
    }
}
