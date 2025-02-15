// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.workspace.jps

import com.intellij.configurationStore.StoreUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.rd.attach
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.TemporaryDirectory
import com.intellij.workspace.api.*
import com.intellij.workspace.ide.WorkspaceModelChangeListener
import com.intellij.workspace.ide.WorkspaceModelInitialTestContent
import com.intellij.workspace.ide.WorkspaceModelTopics
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class LegacyBridgeProjectLibraryTest {

  @Rule
  @JvmField
  var application = ApplicationRule()

  @Rule
  @JvmField
  var temporaryDirectoryRule = TemporaryDirectory()

  @Rule
  @JvmField
  var disposableRule = DisposableRule()

  private lateinit var project: Project
  private lateinit var events: MutableList<EntityChange<LibraryEntity>>

  @Before
  fun prepareProject() {
    val tempDir = temporaryDirectoryRule.newPath("project").toFile()

    project = WorkspaceModelInitialTestContent.withInitialContent(TypedEntityStorageBuilder.create()) {
      ProjectManager.getInstance().createProject("testProject", File(tempDir, "testProject.ipr").path)!!
    }
    runInEdt { ProjectManagerEx.getInstanceEx().openProject(project) }

    events = mutableListOf()
    val messageBusConnection = project.messageBus.connect(disposableRule.disposable)
    messageBusConnection.subscribe(WorkspaceModelTopics.CHANGED, object : WorkspaceModelChangeListener {
      override fun changed(event: EntityStoreChanged) {
        events.addAll(event.getChanges(LibraryEntity::class.java))
      }
    })

    disposableRule.disposable.attach { runInEdt { ProjectUtil.closeAndDispose(project) } }
  }

  @Test
  fun `test project library rename`() = WriteCommandAction.runWriteCommandAction(project) {
    val libraryName = "ant-lib"
    val newLibraryName = "maven-lib"
    val library = createProjectLibrary(libraryName)
    assertEquals(2, events.size)
    checkLibraryAddedEvent(events[0], libraryName)

    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    assertNotNull(projectLibraryTable.getLibraryByName(libraryName))

    renameProjectLibrary(libraryName, newLibraryName)
    assertNull(projectLibraryTable.getLibraryByName(libraryName))
    assertNotNull(projectLibraryTable.getLibraryByName(newLibraryName))
    assertSame(library, projectLibraryTable.getLibraryByName(newLibraryName))
    assertEquals(3, events.size)
    checkLibraryReplacedEvent(events[2], libraryName, newLibraryName)

    renameProjectLibrary(newLibraryName, libraryName)
    assertNull(projectLibraryTable.getLibraryByName(newLibraryName))
    assertNotNull(projectLibraryTable.getLibraryByName(libraryName))
    assertSame(library, projectLibraryTable.getLibraryByName(libraryName))
    assertEquals(4, events.size)
    checkLibraryReplacedEvent(events[3], newLibraryName, libraryName)
  }

  @Test
  fun `test project libraries name swapping`() = WriteCommandAction.runWriteCommandAction(project) {
    val interimLibraryName = "tmp-lib"
    val antLibraryName = "ant-lib"
    val mavenLibraryName = "maven-lib"
    val antLibrary = createProjectLibrary(antLibraryName)
    // Check events from listener one event for create another for add roots
    assertEquals(2, events.size)
    checkLibraryAddedEvent(events[0], antLibraryName)

    val mavenLibrary = createProjectLibrary(mavenLibraryName)
    // Check events from listener
    assertEquals(4, events.size)
    checkLibraryAddedEvent(events[2], mavenLibraryName)

    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    assertNotNull(projectLibraryTable.getLibraryByName(antLibraryName))
    assertNotNull(projectLibraryTable.getLibraryByName(mavenLibraryName))

    // Rename library to intermediate name
    renameProjectLibrary(antLibraryName, interimLibraryName)
    assertNull(projectLibraryTable.getLibraryByName(antLibraryName))
    assertEquals(5, events.size)
    checkLibraryReplacedEvent(events[4], antLibraryName, interimLibraryName)

    // Swap name for the first library
    renameProjectLibrary(mavenLibraryName, antLibraryName)
    assertNull(projectLibraryTable.getLibraryByName(mavenLibraryName))
    assertNotNull(projectLibraryTable.getLibraryByName(antLibraryName))
    assertSame(mavenLibrary, projectLibraryTable.getLibraryByName(antLibraryName))
    assertEquals(6, events.size)
    checkLibraryReplacedEvent(events[5], mavenLibraryName, antLibraryName)

    // Swap name for the second library
    renameProjectLibrary(interimLibraryName, mavenLibraryName)
    assertNull(projectLibraryTable.getLibraryByName(interimLibraryName))
    assertNotNull(projectLibraryTable.getLibraryByName(antLibraryName))
    assertSame(antLibrary, projectLibraryTable.getLibraryByName(mavenLibraryName))
    assertEquals(7, events.size)
    checkLibraryReplacedEvent(events[6], interimLibraryName, mavenLibraryName)
  }

  @Test
  fun `test project libraries name swapping in one transaction`() = WriteCommandAction.runWriteCommandAction(project) {
    val antLibraryName = "ant-lib"
    val mavenLibraryName = "maven-lib"

    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    projectLibraryTable.modifiableModel.let { projectLibTableModel ->
      val antLibraryModel = projectLibTableModel.createLibrary(antLibraryName).modifiableModel
      val mavenLibraryModel = projectLibTableModel.createLibrary(mavenLibraryName).modifiableModel

      antLibraryModel.addRoot(File(project.basePath, "$antLibraryName.jar").path, OrderRootType.CLASSES)
      antLibraryModel.addRoot(File(project.basePath, "$antLibraryName-sources.jar").path, OrderRootType.SOURCES)
      mavenLibraryModel.addRoot(File(project.basePath, "$mavenLibraryName.jar").path, OrderRootType.CLASSES)
      mavenLibraryModel.addRoot(File(project.basePath, "$mavenLibraryName-sources.jar").path, OrderRootType.SOURCES)
      antLibraryModel.name = mavenLibraryName
      mavenLibraryModel.name = antLibraryName

      antLibraryModel.commit()
      mavenLibraryModel.commit()
      projectLibTableModel.commit()
    }

    assertEquals(2, projectLibraryTable.libraries.size)
    val antLibrary = projectLibraryTable.libraries.find { it.name == antLibraryName }!!
    assertEquals(antLibraryName, antLibrary.name)
    assertTrue(antLibrary.getUrls(OrderRootType.CLASSES)[0].contains(mavenLibraryName))

    val mavenLibrary = projectLibraryTable.libraries.find { it.name == mavenLibraryName }!!
    assertEquals(mavenLibraryName, mavenLibrary.name)
    assertTrue(mavenLibrary.getUrls(OrderRootType.CLASSES)[0].contains(antLibraryName))

    assertEquals(2, events.size)
    StoreUtil.saveDocumentsAndProjectSettings(project)
    assertTrue(checkLibraryClassRootOnDisk(antLibraryName, "$mavenLibraryName.jar"))
    assertTrue(checkLibraryClassRootOnDisk(mavenLibraryName, "$antLibraryName.jar"))
  }

  @Test
  fun `test project library already exists exception`() = WriteCommandAction.runWriteCommandAction(project) {
    val libraryName = "ant-lib"
    val anotherLibraryName = "maven-lib"
    createProjectLibrary(libraryName)
    assertEquals(2, events.size)
    checkLibraryAddedEvent(events[0], libraryName)

    // Catch exception during library creation
    try {
      createProjectLibrary(libraryName)
    }
    catch (e: IllegalStateException) {
      assertEquals("Project library named $libraryName already exists", e.message)
    }
    // Check event was not published
    assertEquals(2, events.size)

    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    assertEquals(1, projectLibraryTable.libraries.size)
    createProjectLibrary(anotherLibraryName)
    assertEquals(2, projectLibraryTable.libraries.size)
    assertEquals(4, events.size)
    checkLibraryAddedEvent(events[2], anotherLibraryName)

    // Catch exception during library rename
    try {
      renameProjectLibrary(anotherLibraryName, libraryName)
    }
    catch (e: IllegalStateException) {
      assertEquals("Library named $libraryName already exists", e.message)
    }
    assertEquals(2, projectLibraryTable.libraries.size)
    assertEquals(4, events.size)
  }

  @Test
  fun `test project library creation in one transaction`() = WriteCommandAction.runWriteCommandAction(project) {
    val libraryName = "ant-lib"
    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    projectLibraryTable.modifiableModel.let {
      val library = it.createLibrary(libraryName)
      library.modifiableModel.let { mLib ->
        mLib.addRoot(File(project.basePath, "$libraryName.jar").path, OrderRootType.CLASSES)
        mLib.addRoot(File(project.basePath, "$libraryName-sources.jar").path, OrderRootType.SOURCES)
        mLib.commit()
      }
      it.commit()
    }
    assertEquals(1, events.size)
    val event = events[0]
    assertTrue(event is EntityChange.Added)
    val libraryEntity = (event as EntityChange.Added).entity
    assertEquals(libraryName, libraryEntity.name)
    assertTrue(libraryEntity.tableId is LibraryTableId.ProjectLibraryTableId)
    assertEquals(2, libraryEntity.roots.size)
  }

  private fun checkLibraryAddedEvent(event: EntityChange<LibraryEntity>, libraryName: String) {
    assertTrue(event is EntityChange.Added)
    val libraryEntity = (event as EntityChange.Added).entity
    assertEquals(libraryName, libraryEntity.name)
    assertTrue(libraryEntity.tableId is LibraryTableId.ProjectLibraryTableId)
    assertEquals(0, libraryEntity.roots.size)
    checkLibraryDiskState(libraryName)
  }

  private fun checkLibraryReplacedEvent(event: EntityChange<LibraryEntity>, oldLibraryName: String, newLibraryName: String) {
    assertTrue(event is EntityChange.Replaced)
    val replaced = event as EntityChange.Replaced
    val newEntity = replaced.newEntity
    val oldEntity = replaced.oldEntity
    assertEquals(oldLibraryName, oldEntity.name)
    assertTrue(oldEntity.tableId is LibraryTableId.ProjectLibraryTableId)
    assertEquals(newLibraryName, newEntity.name)
    assertTrue(newEntity.tableId is LibraryTableId.ProjectLibraryTableId)
    assertEquals(2, newEntity.roots.size)
    checkLibraryDiskState(newLibraryName, oldLibraryName)
  }

  private fun checkLibraryDiskState(currentLibraryName: String, previousLibraryName: String = "") {
    val iprFile = File(project.projectFilePath!!)
    StoreUtil.saveDocumentsAndProjectSettings(project)
    val librariesList = JDOMUtil.load(iprFile).getChild("component")!!.getChildren("library")
    assertTrue(librariesList.find { it.getAttribute("name")!!.value == currentLibraryName } != null)
    assertTrue(librariesList.find { it.getAttribute("name")!!.value == previousLibraryName } == null)
  }

  private fun checkLibraryClassRootOnDisk(libraryName: String, classFileName: String): Boolean {
    return JDOMUtil.load(File(project.projectFilePath!!)).getChild("component")
             ?.getChildren("library")?.find { it.getAttribute("name")!!.value == libraryName }
             ?.getChild(OrderRootType.CLASSES.name())
             ?.getChild("root")
             ?.getAttribute("url")?.value?.contains(classFileName) ?: false
  }

  private fun createProjectLibrary(libraryName: String): Library {
    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
    val library = projectLibraryTable.createLibrary(libraryName)

    library.modifiableModel.let {
      it.addRoot(File(project.basePath, "$libraryName.jar").path, OrderRootType.CLASSES)
      it.addRoot(File(project.basePath, "$libraryName-sources.jar").path, OrderRootType.SOURCES)
      it.commit()
    }
    return library
  }

  private fun renameProjectLibrary(oldLibraryName: String, newLibraryName: String) {
    val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

    projectLibraryTable.getLibraryByName(oldLibraryName)?.modifiableModel?.let {
      it.name = newLibraryName
      it.commit()
    }
  }
}