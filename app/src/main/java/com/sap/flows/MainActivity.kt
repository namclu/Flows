package com.sap.flows

//imports for entire Flows tutorial are added here
import android.R
import android.app.Activity
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.Toast
import com.sap.cloud.mobile.flow.FlowActionHandler
import com.sap.cloud.mobile.flow.FlowContext
import com.sap.cloud.mobile.flow.FlowManagerService
import com.sap.cloud.mobile.flow.ServiceConnection
import com.sap.cloud.mobile.flow.Step
import com.sap.cloud.mobile.flow.onboarding.OnboardingContext
import com.sap.cloud.mobile.flow.onboarding.basicauth.BasicAuthStep
import com.sap.cloud.mobile.flow.onboarding.basicauth.BasicAuthStoreStep
import com.sap.cloud.mobile.flow.onboarding.eulascreen.EulaScreenStep
import com.sap.cloud.mobile.flow.onboarding.presenter.FlowPresentationActionHandlerImpl
import com.sap.cloud.mobile.flow.onboarding.storemanager.ChangePasscodeStep
import com.sap.cloud.mobile.flow.onboarding.storemanager.PasscodePolicyStoreStep
import com.sap.cloud.mobile.flow.onboarding.storemanager.SettingsDownloadStep
import com.sap.cloud.mobile.flow.onboarding.storemanager.SettingsStoreStep
import com.sap.cloud.mobile.flow.onboarding.storemanager.StoreManagerStep
import com.sap.cloud.mobile.flow.onboarding.welcomescreen.WelcomeScreenStep
import com.sap.cloud.mobile.flow.onboarding.welcomescreen.WelcomeScreenStoreStep
import com.sap.cloud.mobile.foundation.common.EncryptionError
import com.sap.cloud.mobile.foundation.common.SettingsParameters
import com.sap.cloud.mobile.foundation.configurationprovider.ConfigurationProvider
import com.sap.cloud.mobile.foundation.configurationprovider.JsonConfigurationProvider
import com.sap.cloud.mobile.foundation.logging.Logging
import com.sap.cloud.mobile.foundation.securestore.OpenFailureException
import com.sap.cloud.mobile.onboarding.launchscreen.LaunchScreenSettings
import com.sap.cloud.mobile.onboarding.qrcodereader.QRCodeConfirmSettings
import com.sap.cloud.mobile.onboarding.qrcodereader.QRCodeReaderSettings

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.MalformedURLException

import ch.qos.logback.classic.Level
import okhttp3.OkHttpClient
import org.intellij.lang.annotations.Flow

class MainActivity : AppCompatActivity() {
    private var flowManagerService: FlowManagerService? = null
    private var flowContext: OnboardingContext? = null
    private val appID = "com.sap.flows"
    private val myLogUploadListener: Logging.UploadListener? = null
    private val settingsDownloadStep = SettingsDownloadStep()
    private val eulaScreenStep = EulaScreenStep()

    private var connection: com.sap.cloud.mobile.flow.ServiceConnection = object :
        com.sap.cloud.mobile.flow.ServiceConnection() {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            flowManagerService = (service as FlowManagerService.LocalBinder).getService()
            startOnboardingFlow()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            flowManagerService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.hide()
        setContentView(com.sap.flows.R.layout.splash_screen)

        initializeLogging(Level.TRACE)
        LOGGER.debug("Log level in onCreate is: " + Logging.getRootLogger().getLevel().toString())

        // Uncomment the below line to not show the passcode screen.  Requires that Passcode Policy is disabled in the management cockpit
        settingsDownloadStep.passcodePolicy = null
//        eulaScreenStep.eulaVersion = "0.1"

        this.bindService(
            Intent(this, FlowManagerService::class.java),
            this.connection, Activity.BIND_AUTO_CREATE
        )
    }

    private fun initializeLogging(level: Level) {
        val cb = Logging.ConfigurationBuilder()
            .logToConsole(true)
            .initialLevel(level)  // levels in order are all, trace, debug, info, warn, error, off
        Logging.initialize(this.applicationContext, cb)
    }

    private fun startOnboardingFlow() {
        flowContext = OnboardingContext()

        // setting details on the welcome screen and store
        val welcomeScreenStep = WelcomeScreenStep()
        welcomeScreenStep.setApplicationId(appID)
        welcomeScreenStep.setApplicationVersion("1.0")
        welcomeScreenStep.setDeviceId(
            android.provider.Settings.Secure.getString(
                this.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        )

        val lss = LaunchScreenSettings()
        lss.setDemoAvailable(false)
        lss.setLaunchScreenTitles(arrayOf("Wiz App"))
        lss.setLaunchScreenHeadline("Now with Flows!")
        lss.setLaunchScreenDescriptions(arrayOf("See how easy it is to onboard with Flows"))
        lss.setLaunchScreenImages(intArrayOf(com.sap.flows.R.drawable.graphic_airplane))
        welcomeScreenStep.setWelcomeScreenSettings(lss)

        // adds the QR code activation screen during onboarding
        //welcomeScreenStep.setProviders(new ConfigurationProvider[] {new JsonConfigurationProvider()});

        // skips the Scan Succeeded screen after scanning the QR code
        //QRCodeConfirmSettings qrcs = new QRCodeConfirmSettings();
        //QRCodeReaderSettings qrcrs = new QRCodeReaderSettings();
        //qrcrs.setSkipConfirmScreen(true);
        //welcomeScreenStep.setQrCodeConfirmSettings(qrcs);
        //welcomeScreenStep.setQrCodeReaderSettings(qrcrs);

        // Creating flow and configuring steps
        val flow = com.sap.cloud.mobile.flow.Flow("onboard")
        flow.setSteps(
            arrayOf<Step>(
                PasscodePolicyStoreStep(), // Creates the passcode policy store (RLM_SECURE_STORE)
                welcomeScreenStep, // Shows the welcome screen and getting the configuration data
                BasicAuthStep(), // Authenticates with Mobile Services
                settingsDownloadStep, // Get the client policy data from the server
                StoreManagerStep(), // Manages the Application Store (APP_SECURE_STORE), encrypted using passcode key
                BasicAuthStoreStep(), // Persists the credentials into the application Store
                WelcomeScreenStoreStep(), // Persists the configuration data into the application store
                SettingsStoreStep(), // Persists the passcode policy into the application store
                eulaScreenStep                  // Presents the EULA screen and persists the version of the EULA into the application store
            )
        )

        // Preparing the flow context
        flowContext!!.setContext(application)
        flowContext!!.setFlowPresentationActionHandler(FlowPresentationActionHandlerImpl(this))

        flowManagerService!!.execute(flow, flowContext!!, object : FlowActionHandler {
            override fun onFailure(t: Throwable) {
                // flowManagerService failed to execute so create an alert dialog to inform users of errors
                LOGGER.debug("Failed to onboard.  " + t.message)

                showAlertDialog("Onboard", t)
            }

            override fun onSuccess(result: FlowContext) {
                initializeLogging(Level.DEBUG) // TODO remove when https://support.wdf.sap.corp/sap/support/message/1980000361 is fixed

                LOGGER.debug("Successfully onboarded")

                // remove the splash screen and replace it with the actual working app screen
                supportActionBar!!.show()
                setContentView(com.sap.flows.R.layout.activity_main)
            }
        })
    }

    fun onUploadLog(view: View) {
        LOGGER.debug("In onUploadLog")
    }

    fun onChange(view: View) {
        LOGGER.debug("In onChange")
    }

    fun onReset(view: View) {
        LOGGER.debug("In onReset")
    }

    fun showAlertDialog(flow: String, t: Throwable) {
        // create an alert dialog because an error has been thrown
        val alertDialog = AlertDialog.Builder(this@MainActivity).create()
        alertDialog.setTitle("Failed to execute $flow Flow")
//        alertDialog.setMessage(if (t.message == "Eula Rejected") "EULA Rejected" else "" + t.message)

        // dismisses the dialog if OK is clicked, but if the EULA was rejected then app is reset
        alertDialog.setButton(
            AlertDialog.BUTTON_POSITIVE, "OK"
        ) { dialog, which ->
            //                        if (t.getMessage().equals("Eula Rejected") || flow.equals("Onboard")) {
            //                            startResetFlow();
            //                        }
            dialog.dismiss()
        }

        // changes the colour scheme
        alertDialog.setOnShowListener {
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#008577"))
        }

        alertDialog.show()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(MyApplication::class.java)
    }
}
