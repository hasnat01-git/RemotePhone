package com.hasnat.remotephone.contract;

public interface CallContract {

    interface View {
        void showIncomingCall(String number);
        void showCallAnswered();
        void showCallEnded();
        void showCallRejected();
        void showError(String message);
    }

    interface Presenter {
        void handleIncomingCall(String number);
        void answerCall();
        void endCall();
        void rejectCall();
        void detach();
    }
}
