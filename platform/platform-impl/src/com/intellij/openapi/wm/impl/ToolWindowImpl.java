// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl;

import com.intellij.ide.UiActivity;
import com.intellij.ide.UiActivityMonitor;
import com.intellij.ide.impl.ContentManagerWatcher;
import com.intellij.notification.EventLog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.BusyObject;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.commands.FinalizableCommand;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.impl.ContentImpl;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ui.update.Activatable;
import com.intellij.util.ui.update.UiNotifyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class ToolWindowImpl implements ToolWindowEx {
  private static final Logger LOG = Logger.getInstance(ToolWindowImpl.class);

  private final ToolWindowManagerImpl myToolWindowManager;
  private final String myId;
  @NotNull
  private final Disposable parentDisposable;
  private final JComponent myComponent;
  private boolean myAvailable = true;
  private final ContentManager myContentManager;
  private Icon myIcon;
  private String myStripeTitle;

  private static final Content EMPTY_CONTENT = new ContentImpl(new JLabel(), "", false);
  private final ToolWindowContentUi myContentUI;

  private InternalDecorator myDecorator;

  private boolean myHideOnEmptyContent;
  private boolean myPlaceholderMode;
  private ToolWindowFactory myContentFactory;

  private final BusyObject.Impl myShowing = new BusyObject.Impl() {
    @Override
    public boolean isReady() {
      return myComponent != null && myComponent.isShowing();
    }
  };
  private String myHelpId;

  ToolWindowImpl(@NotNull ToolWindowManagerImpl toolWindowManager,
                 @NotNull String id,
                 boolean canCloseContent,
                 @Nullable JComponent component,
                 @NotNull Disposable parentDisposable) {
    myToolWindowManager = toolWindowManager;
    myId = id;
    this.parentDisposable = parentDisposable;

    ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
    myContentUI = new ToolWindowContentUi(this);
    myContentManager = contentFactory.createContentManager(myContentUI, canCloseContent, toolWindowManager.getProject());
    Disposer.register(parentDisposable, myContentManager);

    if (component != null) {
      Content content = contentFactory.createContent(component, "", false);
      myContentManager.addContent(content);
      myContentManager.setSelectedContent(content, false);
    }

    myComponent = myContentManager.getComponent();

    InternalDecorator.installFocusTraversalPolicy(myComponent, new LayoutFocusTraversalPolicy());

    Disposer.register(myContentManager, new UiNotifyConnector(myComponent, new Activatable() {
      @Override
      public void showNotify() {
        myShowing.onReady();
      }
    }));
  }

  @NotNull
  @Override
  public Disposable getDisposable() {
    return parentDisposable;
  }

  @Override
  public void remove() {
    myToolWindowManager.unregisterToolWindow(myId);
  }

  @Override
  public final void activate(final Runnable runnable) {
    activate(runnable, true);
  }

  @Override
  public void activate(@Nullable final Runnable runnable, final boolean autoFocusContents) {
    activate(runnable, autoFocusContents, true);
  }

  @Override
  public void activate(@Nullable Runnable runnable, boolean autoFocusContents, boolean forced) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    UiActivity activity = new UiActivity.Focus("toolWindow:" + myId);
    UiActivityMonitor.getInstance().addActivity(myToolWindowManager.getProject(), activity, ModalityState.NON_MODAL);

    myToolWindowManager.activateToolWindow(myId, forced, autoFocusContents);
    myToolWindowManager.invokeLater(() -> {
      if (runnable != null) {
        runnable.run();
      }
      UiActivityMonitor.getInstance().removeActivity(myToolWindowManager.getProject(), activity);
    });
  }

  @Override
  public final boolean isActive() {
    ApplicationManager.getApplication().assertIsDispatchThread();

    JFrame frame = WindowManagerEx.getInstanceEx().getFrame(myToolWindowManager.getProject());
    if (frame == null || !frame.isActive() || myToolWindowManager.isEditorComponentActive()) {
      return false;
    }

    ActionManager actionManager = ActionManager.getInstance();
    if (actionManager instanceof ActionManagerImpl
        && !((ActionManagerImpl)actionManager).isActionPopupStackEmpty()
        && !((ActionManagerImpl)actionManager).isToolWindowContextMenuVisible()) {
      return false;
    }

    return myToolWindowManager.isToolWindowActive(myId) || (myDecorator != null && myDecorator.isFocused());
  }

  @NotNull
  @Override
  public ActionCallback getReady(@NotNull final Object requestor) {
    final ActionCallback result = new ActionCallback();
    myShowing.getReady(this).doWhenDone(() -> {
      ArrayList<FinalizableCommand> cmd = new ArrayList<>();
      cmd.add(new FinalizableCommand(null) {
        @Override
        public boolean willChangeState() {
          return false;
        }

        @Override
        public void run() {
          IdeFocusManager.getInstance(myToolWindowManager.getProject()).doWhenFocusSettlesDown(() -> {
            if (myContentManager.isDisposed()) return;
            myContentManager.getReady(requestor).notify(result);
          });
        }
      });
      myToolWindowManager.execute(cmd);
    });
    return result;
  }

  @Override
  public final void show(final Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.showToolWindow(myId);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public final void hide(@Nullable final Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.hideToolWindow(myId, false);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public final boolean isVisible() {
    return myToolWindowManager.isToolWindowRegistered(myId) && myToolWindowManager.isToolWindowVisible(myId);
  }

  @Override
  public final ToolWindowAnchor getAnchor() {
    return myToolWindowManager.getToolWindowAnchor(myId);
  }

  @Override
  public final void setAnchor(@NotNull final ToolWindowAnchor anchor, @Nullable final Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.setToolWindowAnchor(myId, anchor);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public boolean isSplitMode() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return myToolWindowManager.isSplitMode(myId);
  }

  @Override
  public void setContentUiType(@NotNull ToolWindowContentUiType type, @Nullable Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.setContentUiType(myId, type);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public void setDefaultContentUiType(@NotNull ToolWindowContentUiType type) {
    myToolWindowManager.setDefaultContentUiType(this, type);
  }

  @NotNull
  @Override
  public ToolWindowContentUiType getContentUiType() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return myToolWindowManager.getContentUiType(myId);
  }

  @Override
  public void setSplitMode(final boolean isSideTool, @Nullable final Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.setSideTool(myId, isSideTool);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public final void setAutoHide(final boolean state) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.setToolWindowAutoHide(myId, state);
  }

  @Override
  public final boolean isAutoHide() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return myToolWindowManager.isToolWindowAutoHide(myId);
  }

  @Override
  public final ToolWindowType getType() {
    return myToolWindowManager.getToolWindowType(myId);
  }

  @Override
  public final void setType(@NotNull final ToolWindowType type, @Nullable final Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myToolWindowManager.setToolWindowType(myId, type);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public final ToolWindowType getInternalType() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return myToolWindowManager.getToolWindowInternalType(myId);
  }

  @Override
  public void stretchWidth(int value) {
    myToolWindowManager.stretchWidth(this, value);
  }

  @Override
  public void stretchHeight(int value) {
    myToolWindowManager.stretchHeight(this, value);
  }

  @Override
  public InternalDecorator getDecorator() {
    return myDecorator;
  }

  @Override
  public void setAdditionalGearActions(ActionGroup additionalGearActions) {
    getDecorator().setAdditionalGearActions(additionalGearActions);
  }

  @Override
  public void setTitleActions(AnAction... actions) {
    getDecorator().setTitleActions(actions);
  }

  @Override
  public void setTabActions(AnAction... actions) {
    getDecorator().setTabActions(actions);
  }

  public void setTabDoubleClickActions(@NotNull AnAction... actions) {
    myContentUI.setTabDoubleClickActions(actions);
  }

  @Override
  public final void setAvailable(boolean available, Runnable runnable) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myAvailable = available;
    myToolWindowManager.toolWindowPropertyChanged(this, PROP_AVAILABLE);
    if (runnable != null) {
      myToolWindowManager.invokeLater(runnable);
    }
  }

  @Override
  public void installWatcher(ContentManager contentManager) {
    new ContentManagerWatcher(this, contentManager);
  }

  /**
   * @return {@code true} if the component passed into constructor is not instance of
   *         {@code ContentManager} class. Otherwise it delegates the functionality to the
   *         passed content manager.
   */
  @Override
  public final boolean isAvailable() {
    return myAvailable && myComponent != null;
  }

  @Override
  public final JComponent getComponent() {
    return myComponent;
  }

  @Override
  public ContentManager getContentManager() {
    ensureContentInitialized();
    return myContentManager;
  }

  // to avoid ensureContentInitialized call - myContentManager can report canCloseContents without full initialization
  public boolean canCloseContents() {
    return myContentManager.canCloseContents();
  }

  public ToolWindowContentUi getContentUI() {
    return myContentUI;
  }

  @Override
  public final Icon getIcon() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return myIcon;
  }

  @NotNull
  public final String getId() {
    return myId;
  }

  @Override
  public final String getTitle() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return getSelectedContent().getDisplayName();
  }

  @Override
  @NotNull
  public final String getStripeTitle() {
    ApplicationManager.getApplication().assertIsDispatchThread();
    return ObjectUtils.notNull(myStripeTitle, myId);
  }

  @Override
  public final void setIcon(@NotNull Icon icon) {
    //icon = IconUtil.filterIcon(icon, new UIUtil.GrayFilter(), myComponent);
    ApplicationManager.getApplication().assertIsDispatchThread();
    final Icon oldIcon = getIcon();
    if (!EventLog.LOG_TOOL_WINDOW_ID.equals(getId())) {
      if (oldIcon != icon &&
          !(icon instanceof LayeredIcon) &&
          (Math.abs(icon.getIconHeight() - JBUIScale.scale(13f)) >= 1 || Math.abs(icon.getIconWidth() - JBUIScale.scale(13f)) >= 1)) {
        LOG.warn("ToolWindow icons should be 13x13. Please fix ToolWindow (ID:  " + getId() + ") or icon " + icon);
      }
    }
    //getSelectedContent().setIcon(icon);

    myIcon = new ToolWindowIcon(icon, getId());
    myToolWindowManager.toolWindowPropertyChanged(this, PROP_ICON);
  }

  @Override
  public final void setTitle(String title) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    getSelectedContent().setDisplayName(title);
    myToolWindowManager.toolWindowPropertyChanged(this, PROP_TITLE);
  }

  @Override
  public final void setStripeTitle(@NotNull String stripeTitle) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myStripeTitle = stripeTitle;
    myToolWindowManager.toolWindowPropertyChanged(this, PROP_STRIPE_TITLE);
  }

  private Content getSelectedContent() {
    final Content selected = getContentManager().getSelectedContent();
    return selected != null ? selected : EMPTY_CONTENT;
  }

  public void setDecorator(@NotNull InternalDecorator decorator) {
    myDecorator = decorator;
  }

  public void fireActivated() {
    if (myDecorator != null) {
      myDecorator.fireActivated();
    }
  }

  public void fireHidden() {
    if (myDecorator != null) {
      myDecorator.fireHidden();
    }
  }

  public void fireHiddenSide() {
    if (myDecorator != null) {
      myDecorator.fireHiddenSide();
    }
  }

  public ToolWindowManagerImpl getToolWindowManager() {
    return myToolWindowManager;
  }

  @Nullable
  public ActionGroup getPopupGroup() {
    return myDecorator != null ? myDecorator.createPopupGroup() : null;
  }

  @SuppressWarnings("unused")
  public void removeStripeButton() {
    if (myDecorator != null) {
      myDecorator.removeStripeButton();
    }
  }
  @SuppressWarnings("unused")
  public void showStripeButton() {
    if (myDecorator != null) {
      myDecorator.showStripeButton();
    }
  }

  @Override
  public void setDefaultState(@Nullable final ToolWindowAnchor anchor, @Nullable final ToolWindowType type, @Nullable final Rectangle floatingBounds) {
    myToolWindowManager.setDefaultState(this, anchor, type, floatingBounds);
  }

  @Override
  public void setToHideOnEmptyContent(final boolean hideOnEmpty) {
    myHideOnEmptyContent = hideOnEmpty;
  }

  @Override
  public boolean isToHideOnEmptyContent() {
    return myHideOnEmptyContent;
  }

  @Override
  public void setShowStripeButton(boolean show) {
    myToolWindowManager.setShowStripeButton(myId, show);
  }

  @Override
  public boolean isShowStripeButton() {
    return myToolWindowManager.isShowStripeButton(myId);
  }

  @Override
  public boolean isDisposed() {
    return myContentManager.isDisposed();
  }

  boolean isPlaceholderMode() {
    return myPlaceholderMode;
  }

  void setPlaceholderMode(final boolean placeholderMode) {
    myPlaceholderMode = placeholderMode;
  }

  public void setContentFactory(ToolWindowFactory contentFactory) {
    myContentFactory = contentFactory;
    contentFactory.init(this);
  }

  public void ensureContentInitialized() {
    if (myContentFactory != null) {
      ToolWindowFactory contentFactory = myContentFactory;
      // clear it first to avoid SOE
      myContentFactory = null;
      myContentManager.removeAllContents(false);
      contentFactory.createToolWindowContent(myToolWindowManager.getProject(), this);
    }
  }

  @Override
  public void setHelpId(String helpId) {
    myHelpId = helpId;
  }

  @Nullable
  @Override
  public String getHelpId() {
    return myHelpId;
  }

  @Override
  public void showContentPopup(InputEvent inputEvent) {
    myContentUI.toggleContentPopup();
  }
}
