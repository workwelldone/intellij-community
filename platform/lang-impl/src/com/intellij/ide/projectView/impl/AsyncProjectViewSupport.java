/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ide.projectView.impl;

import com.intellij.ProjectTopics;
import com.intellij.ide.CopyPasteUtil;
import com.intellij.ide.bookmarks.Bookmark;
import com.intellij.ide.bookmarks.BookmarksListener;
import com.intellij.ide.projectView.ProjectViewPsiTreeChangeListener;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.ide.util.treeView.AbstractTreeStructure;
import com.intellij.ide.util.treeView.AbstractTreeUpdater;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FileStatusListener;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.*;
import com.intellij.ui.tree.AsyncTreeModel;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.ui.tree.TreeVisitor;
import com.intellij.util.Consumer;
import com.intellij.util.SmartList;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static com.intellij.ide.util.treeView.TreeState.VISIT;
import static com.intellij.ide.util.treeView.TreeState.expand;
import static com.intellij.util.ui.UIUtil.putClientProperty;

class AsyncProjectViewSupport {
  private static final Logger LOG = Logger.getInstance(AsyncProjectViewSupport.class);
  private final StructureTreeModel myStructureTreeModel;
  private final AsyncTreeModel myAsyncTreeModel;

  public AsyncProjectViewSupport(Disposable parent,
                                 Project project,
                                 JTree tree,
                                 AbstractTreeStructure structure,
                                 Comparator<NodeDescriptor> comparator) {
    myStructureTreeModel = new StructureTreeModel(true);
    myStructureTreeModel.setStructure(structure);
    myStructureTreeModel.setComparator(comparator);
    myAsyncTreeModel = new AsyncTreeModel(myStructureTreeModel, true);
    myAsyncTreeModel.setRootImmediately(myStructureTreeModel.getRootImmediately());
    setModel(tree, myAsyncTreeModel);
    Disposer.register(parent, myAsyncTreeModel);
    MessageBusConnection connection = project.getMessageBus().connect(parent);
    connection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(ModuleRootEvent event) {
        updateAll();
      }
    });
    connection.subscribe(BookmarksListener.TOPIC, new BookmarksListener() {
      @Override
      public void bookmarkAdded(@NotNull Bookmark bookmark) {
        updateByFile(bookmark.getFile());
      }

      @Override
      public void bookmarkRemoved(@NotNull Bookmark bookmark) {
        updateByFile(bookmark.getFile());
      }

      @Override
      public void bookmarkChanged(@NotNull Bookmark bookmark) {
        updateByFile(bookmark.getFile());
      }
    });
    PsiManager.getInstance(project).addPsiTreeChangeListener(new ProjectViewPsiTreeChangeListener(project) {
      @Override
      protected boolean isFlattenPackages() {
        return structure instanceof AbstractProjectTreeStructure && ((AbstractProjectTreeStructure)structure).isFlattenPackages();
      }

      @Override
      protected AbstractTreeUpdater getUpdater() {
        return null;
      }

      @Override
      protected DefaultMutableTreeNode getRootNode() {
        return null;
      }

      @Override
      protected void addSubtreeToUpdateByRoot() {
        updateAll();
      }

      @Override
      protected boolean addSubtreeToUpdateByElement(PsiElement element) {
        updateByElement(element);
        return true;
      }
    }, parent);
    FileStatusManager.getInstance(project).addFileStatusListener(new FileStatusListener() {
      @Override
      public void fileStatusesChanged() {
        updateAll();
      }

      @Override
      public void fileStatusChanged(@NotNull VirtualFile file) {
        updateByFile(file);
      }
    }, parent);
    CopyPasteManager.getInstance().addContentChangedListener(new CopyPasteUtil.DefaultCopyPasteListener(this::updateByElement), parent);
    WolfTheProblemSolver.getInstance(project).addProblemListener(new WolfTheProblemSolver.ProblemListener() {
      @Override
      public void problemsAppeared(@NotNull VirtualFile file) {
        updateByFile(file);
      }

      @Override
      public void problemsDisappeared(@NotNull VirtualFile file) {
        updateByFile(file);
      }
    }, parent);
  }

  public void setComparator(Comparator<NodeDescriptor> comparator) {
    myStructureTreeModel.setComparator(comparator);
  }

  public void select(JTree tree, Object object, VirtualFile file) {
    if (object instanceof AbstractTreeNode) {
      AbstractTreeNode node = (AbstractTreeNode)object;
      object = node.getValue();
      LOG.debug("select AbstractTreeNode");
    }
    PsiElement element = object instanceof PsiElement ? (PsiElement)object : null;
    TreeVisitor visitor = createVisitor(element, file, null);
    if (visitor != null) {
      expand(tree, promise -> myAsyncTreeModel.visit(visitor).processed(path -> {
        if (path != null) TreeUtil.selectPath(tree, path);
        promise.setResult(null);
      }));
    }
  }

  public void updateAll() {
    LOG.debug(new RuntimeException("reload a whole tree"));
    myStructureTreeModel.invalidate();
  }

  public void update(@NotNull TreePath path) {
    myStructureTreeModel.invalidate(path, true);
  }

  public void update(@NotNull List<TreePath> list) {
    for (TreePath path : list) update(path);
  }

  public void updateByFile(@NotNull VirtualFile file) {
    update(null, file, this::update);
  }

  public void updateByElement(@NotNull PsiElement element) {
    update(element, null, this::update);
  }

  private void update(PsiElement element, VirtualFile file, Consumer<List<TreePath>> consumer) {
    SmartList<TreePath> list = new SmartList<>();
    TreeVisitor visitor = createVisitor(element, file, path -> !list.add(path));
    if (visitor != null) myAsyncTreeModel.visit(visitor).done(path -> consumer.consume(list));
  }

  private static TreeVisitor createVisitor(PsiElement element, VirtualFile file, Predicate<TreePath> predicate) {
    if (element != null) return new ProjectViewNodeVisitor(element, file, predicate);
    if (file != null) return new ProjectViewFileVisitor(file, predicate);
    LOG.warn("cannot create visitor without element and/or file");
    return null;
  }

  private static void setModel(@NotNull JTree tree, @NotNull AsyncTreeModel model) {
    tree.setModel(model);
    putClientProperty(tree, VISIT, visitor -> model.visit(visitor, true));
    Disposer.register(model, () -> {
      putClientProperty(tree, VISIT, null);
      tree.setModel(null);
    });
  }
}