/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.appentry

import android.content.res.Configuration
import android.content.{DialogInterface, Intent}
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.{Fragment, FragmentTransaction}
import android.view.View
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api._
import com.waz.service.{AccountsService, ZMessaging}
import com.waz.threading.Threading
import com.waz.utils.returning
import com.waz.zclient._
import com.waz.zclient.appentry.fragments.{TeamNameFragment, _}
import com.waz.zclient.newreg.fragments.country.CountryController
import com.waz.zclient.preferences.PreferencesController
import com.waz.zclient.tracking.CrashController
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceTextView}
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.LoadingIndicatorView
import net.hockeyapp.android.NativeCrashManager
import com.waz.zclient.utils.RichView

import scala.collection.JavaConverters._
import AppEntryActivity._
import android.support.v4.app.FragmentManager.OnBackStackChangedListener
import com.waz.zclient.appentry.controllers.InvitationsController

object AppEntryActivity {
  val TAG: String = classOf[AppEntryActivity].getName
  private val HTTPS_PREFIX: String = "https://"
  private val HTTP_PREFIX: String = "http://"
  val PREFETCH_IMAGE_WIDTH: Int = 4

  val MethodArg: String = "method_arg"
  val LoginArgVal: Int = 0
  val CreateTeamArgVal: Int = 1

  def getLoginArgs: Bundle =
    returning(new Bundle()) { b =>
      b.putInt(MethodArg, LoginArgVal)
    }

  def getCreateTeamArgs: Bundle =
    returning(new Bundle()) { b =>
      b.putInt(MethodArg, CreateTeamArgVal)
    }
}

class AppEntryActivity extends BaseActivity
  with VerifyPhoneFragment.Container
  with PhoneSetNameFragment.Container
  with CountryDialogFragment.Container
  with SignInFragment.Container
  with InsertPasswordFragment.Container {

  import Threading.Implicits.Ui

  private var unsplashInitLoadHandle: LoadHandle = null
  private lazy val progressView = ViewUtils.getView(this, R.id.liv__progress).asInstanceOf[LoadingIndicatorView]
  private lazy val countryController: CountryController = new CountryController(this)
  private lazy val invitesController = inject[InvitationsController]
  private var createdFromSavedInstance: Boolean = false
  private var isPaused: Boolean = false

  private lazy val accountsService = inject[AccountsService]

  private lazy val closeButton = returning(ViewUtils.getView(this, R.id.close_button).asInstanceOf[GlyphTextView]) { v =>
    accountsService.zmsInstances.map(_.nonEmpty).onUi(vis => v.setVisibility(if (vis) View.VISIBLE else View.GONE))
  }

  private lazy val skipButton = returning(findById[TypefaceTextView](R.id.skip_button)) { v =>
    invitesController.invitations.map(_.isEmpty).map {
      case true => R.string.teams_invitations_skip
      case false => R.string.teams_invitations_done
    }.onUi(t => v.setText(t))
  }

  ZMessaging.currentGlobal.blacklist.upToDate.onUi {
    case false =>
      startActivity(new Intent(this, classOf[ForceUpdateActivity]))
      finish()
    case _ =>
  }

  override def onBackPressed(): Unit = {
    getSupportFragmentManager.getFragments.asScala.find {
      case f: OnBackPressedListener if f.onBackPressed() => true
      case _ => false
    }.fold(finish())(_ => ())
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    if (getActionBar != null) getActionBar.hide()
    super.onCreate(savedInstanceState)
    ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, this)
    setContentView(R.layout.activity_signup)
    enableProgress(false)
    createdFromSavedInstance = savedInstanceState != null

    closeButton.onClick {
      accountsService.accountsWithManagers.map(_.headOption).head.foreach { userId =>
        accountsService.setAccount(userId)
        Option(getIntent.getExtras).map(_.getInt(MethodArg, -1)) match {
          case Some(LoginArgVal | CreateTeamArgVal) =>
            startActivity(Intents.OpenSettingsIntent(this))
          case _ =>
            onEnterApplication(false)
        }
      }
    }

    withFragmentOpt(AppLaunchFragment.Tag) {
      case Some(_) =>
      case None =>
        Option(getIntent.getExtras).map(_.getInt(MethodArg)) match {
          case Some(LoginArgVal) => showFragment(SignInFragment(), SignInFragment.Tag, animated = false)
          case Some(CreateTeamArgVal) => showFragment(TeamNameFragment(), TeamNameFragment.Tag, animated = false)
          case _ => showFragment(AppLaunchFragment(), AppLaunchFragment.Tag, animated = false)
      }
    }

    skipButton.setVisibility(View.GONE)
    getSupportFragmentManager.addOnBackStackChangedListener(new OnBackStackChangedListener {
      override def onBackStackChanged(): Unit = {
        withFragmentOpt(InviteToTeamFragment.Tag) {
          case Some(_) => skipButton.setVisibility(View.VISIBLE)
          case _ => skipButton.setVisibility(View.GONE)
        }
      }
    })
    skipButton.onClick(onEnterApplication(false))
  }

  override protected def onResume(): Unit = {
    super.onResume()

    val trackingEnabled: Boolean = injectJava(classOf[PreferencesController]).isAnalyticsEnabled
    if (trackingEnabled) {
      //TODO move to new preferences
      CrashController.checkForCrashes(getApplicationContext, getControllerFactory.getUserPreferencesController.getDeviceId)
    }
    else {
      CrashController.deleteCrashReports(getApplicationContext)
      NativeCrashManager.deleteDumpFiles(getApplicationContext)
    }
  }

  override protected def onPostResume(): Unit = {
    super.onPostResume()
    isPaused = false
  }

  override protected def onPause(): Unit = {
    isPaused = true
    super.onPause()
  }

  override def onStop(): Unit = {
    if (unsplashInitLoadHandle != null) {
      unsplashInitLoadHandle.cancel()
      unsplashInitLoadHandle = null
    }
    super.onStop()
  }

  override protected def onActivityResult(requestCode: Int, resultCode: Int, data: Intent): Unit = {
    info(s"OnActivity result: $requestCode, $resultCode")
    super.onActivityResult(requestCode, resultCode, data)
    getSupportFragmentManager.findFragmentById(R.id.fl_main_content).onActivityResult(requestCode, resultCode, data)
  }

  def enableProgress(enabled: Boolean): Unit = {
    if (enabled)
      progressView.show(LoadingIndicatorView.SpinnerWithDimmedBackground(), darkTheme = true)
    else
      progressView.hide()
  }

  def abortAddAccount(): Unit = {
    enableProgress(true)
//    ZMessaging.currentAccounts.loggedInAccounts.head.map { accounts =>
//      accounts.headOption.fold {
//        if (appEntryController.entryStage.currentValue.exists(_ != NoAccountState(FirstScreen))) {
//          appEntryController.gotToFirstPage()
//        } else {
//          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
//            finish()
//          else
//            finishAfterTransition()
//        }
//      } { acc =>
//        ZMessaging.currentAccounts.switchAccount(acc.id).map { _ =>
//          onEnterApplication(openSettings = true)
//        }
//      }
//    }
  }

  def onOpenUrl(url: String): Unit =
    try {
      val prefixedUrl =
        if (!url.startsWith(AppEntryActivity.HTTP_PREFIX) && !url.startsWith(AppEntryActivity.HTTPS_PREFIX))
          AppEntryActivity.HTTP_PREFIX + url
        else
          url
      startActivity(returning(new Intent(Intent.ACTION_VIEW, Uri.parse(prefixedUrl)))(_.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
    }
    catch {
      case _: Exception => error(s"Failed to open URL: $url")
    }

  def onShowCreateTeamFragment(): Unit =
    withFragmentOpt(TeamNameFragment.Tag) {
      case Some(_) =>
      case None => showFragment(TeamNameFragment(), TeamNameFragment.Tag)
    }

  def onShowSignInPage(): Unit =
    withFragmentOpt(SignInFragment.Tag) {
      case Some(_) =>
      case None =>
        showFragment(new SignInFragment, SignInFragment.Tag)
    }

  def onShowFirstLaunchPage(): Unit =
    showFragment(FirstLaunchAfterLoginFragment(), FirstLaunchAfterLoginFragment.Tag)

  def onEnterApplication(openSettings: Boolean): Unit = {
    getControllerFactory.getVerificationController.finishVerification()
    startActivity(Intents.EnterAppIntent(openSettings)(this))
    finish()
  }

  private def setDefaultAnimation(transaction: FragmentTransaction): FragmentTransaction = {
    transaction.setCustomAnimations(
      R.anim.fragment_animation_second_page_slide_in_from_right,
      R.anim.fragment_animation_second_page_slide_out_to_left,
      R.anim.fragment_animation_second_page_slide_in_from_left,
      R.anim.fragment_animation_second_page_slide_out_to_right)
    transaction
  }

  def openCountryBox(): Unit = {
    getSupportFragmentManager
      .beginTransaction
      .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
      .add(new CountryDialogFragment, CountryDialogFragment.TAG)
      .addToBackStack(CountryDialogFragment.TAG)
      .commit
    KeyboardUtils.hideKeyboard(this)
  }

  def getCountryController: CountryController = countryController

  def dismissCountryBox(): Unit = {
    getSupportFragmentManager.popBackStackImmediate
    KeyboardUtils.showKeyboard(this)
  }

  def showError(entryError: EntryError, okCallback: => Unit = {}): Unit =
    ViewUtils.showAlertDialog(this,
      entryError.headerResource,
      entryError.bodyResource,
      R.string.reg__phone_alert__button,
      new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, which: Int): Unit = {
          dialog.dismiss()
          okCallback
        }
      },
      false)

  def onShowInsertPassword(): Unit =
    showFragment(InsertPasswordFragment(), InsertPasswordFragment.Tag)

  def showFragment(f: => Fragment, tag: String, animated: Boolean = true): Unit = {
    val transaction = getSupportFragmentManager.beginTransaction()
    if (animated) setDefaultAnimation(transaction)
      transaction
      .replace(R.id.fl_main_content, f, tag)
      .addToBackStack(tag)
      .commit
    enableProgress(false)
  }
}
