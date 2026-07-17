package com.lazyreader.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lazyreader.data.RecentDocument
import com.lazyreader.ui.about.AboutScreen
import com.lazyreader.ui.dashboard.DashboardScreen
import com.lazyreader.ui.dashboard.DashboardViewModel
import com.lazyreader.ui.reader.EpubReaderScreen
import com.lazyreader.ui.reader.ReaderScreen
import kotlinx.coroutines.launch

@Composable
fun LazyReaderApp() {
    val navController = rememberNavController()
    val viewModel: DashboardViewModel = viewModel()
    val documents by viewModel.recentDocuments.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val uriString = viewModel.importDocument(uri)
                navController.navigate("reader/${Uri.encode(uriString)}")
            }
        }
    }

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                documents = documents,
                onImportClick = { importLauncher.launch(arrayOf("application/pdf", "application/epub+zip")) },
                onDocumentClick = { document ->
                    navController.navigate("reader/${Uri.encode(document.uri)}")
                },
                onDeleteClick = viewModel::deleteDocument,
                onAboutClick = { navController.navigate("about") },
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "reader/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { backStackEntry ->
            // Navigation Compose already URL-decodes path arguments when extracting
            // them from the route, so this is the original, un-encoded content URI.
            val documentUri = backStackEntry.arguments?.getString("uri").orEmpty()
            val document = documents.find { it.uri == documentUri }
            if (document?.type == RecentDocument.TYPE_EPUB) {
                EpubReaderScreen(
                    documentUri = documentUri,
                    displayName = document.displayName,
                    startChapter = document.currentPage,
                    onBack = { navController.popBackStack() },
                )
            } else {
                ReaderScreen(
                    documentUri = documentUri,
                    displayName = document?.displayName ?: "",
                    startPage = document?.currentPage ?: 0,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
