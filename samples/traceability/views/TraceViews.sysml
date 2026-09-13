package TraceViews {
    private import TraceModel::*;
    private import StandardViewDefinitions::*;

    view 'trace coverage' : GeneralView {
        expose vehicleSpeedReq;
        expose motorSpeedReq;
        expose vehicle;
        expose motor;
        expose speedTest;
    }
}
