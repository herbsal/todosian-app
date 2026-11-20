package com.isotjs.todosian

import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isotjs.todosian.data.FileRepository
import com.isotjs.todosian.data.PreferencesManager
import com.isotjs.todosian.data.settings.AppSettingsRepository
import com.isotjs.todosian.ui.addtask.AddTaskScreen
import com.isotjs.todosian.ui.category.CategoryScreen
import com.isotjs.todosian.ui.dailyfocus.DailyFocusScreen
import com.isotjs.todosian.ui.home.HomeScreen
import com.isotjs.todosian.ui.onboarding.OnboardingScreen
import com.isotjs.todosian.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TodosianApp(
    fileRepository: FileRepository,
    appSettingsRepository: AppSettingsRepository,
    preferencesManager: PreferencesManager,
    addTaskSignal: StateFlow<AddTaskRequest>,
    initialAddTaskRequest: AddTaskRequest,
) {
    val navController = rememberNavController()
    val addTaskRequest by addTaskSignal.collectAsStateWithLifecycle(initialValue = initialAddTaskRequest)
    val startDestination = remember(fileRepository, addTaskRequest) {
        val categoryUri = addTaskRequest.categoryUri
        when {
            fileRepository.getFolderUri() == null -> Routes.Onboarding
            addTaskRequest.tick > 0L && categoryUri != null ->
                Routes.category(categoryUri.toUri(), addTodo = true, text = addTaskRequest.text)
            addTaskRequest.tick > 0L -> Routes.addTask(addTaskRequest.text)
            else -> Routes.Home
        }
    }

    LaunchedEffect(addTaskRequest) {
        val request = addTaskRequest
        if (request.tick > 0L && fileRepository.getFolderUri() != null) {
            val destination = navController.currentDestination?.route
            // Shared text always opens a fresh sheet, even if that screen is already showing.
            val shared = request.text != null
            when {
                request.categoryUri != null && (shared || destination != Routes.Category) ->
                    navController.navigate(
                        Routes.category(request.categoryUri.toUri(), addTodo = true, text = request.text),
                    ) { launchSingleTop = !shared }

                request.categoryUri == null && (shared || destination != Routes.AddTask) ->
                    navController.navigate(Routes.addTask(request.text)) { launchSingleTop = !shared }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
        // Predictive back gestures (swipe from edge) use these instead of popEnter/popExit.
        // Without them, the system's default scale-down "zoom out" animation plays, which
        // clashes with the app's slide-based motion language.
        predictivePopEnterTransition = { _ ->
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            ) + fadeIn(animationSpec = tween(150))
        },
        predictivePopExitTransition = { _ ->
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(300),
            ) + fadeOut(animationSpec = tween(150))
        },
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(
                fileRepository = fileRepository,
                onFinished = {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Home) { backStackEntry ->
            val refreshSignal = backStackEntry.savedStateHandle
                .getStateFlow(KEY_REFRESH_HOME, 0L)
                .collectAsStateWithLifecycle()
                .value

            HomeScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                onOpenCategory = { uri ->
                    navController.navigate(Routes.category(uri))
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
                onOpenDailyFocus = {
                    navController.navigate(Routes.DailyFocus)
                },
                onOpenView = { view ->
                    navController.navigate(Routes.savedView(view.folder, view.name))
                },
                refreshSignal = refreshSignal,
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                preferencesManager = preferencesManager,
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
                onRequireOnboarding = {
                    navController.navigate(Routes.Onboarding) {
                        popUpTo(Routes.Home) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.Category,
            arguments = listOf(
                navArgument(Routes.ARG_CATEGORY_URI) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_CATEGORY_ADD) {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument(Routes.ARG_TEXT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val encoded = backStackEntry.arguments?.getString(Routes.ARG_CATEGORY_URI).orEmpty()
            val uri = encoded.toUri()
            val autoOpenAddTodo = backStackEntry.arguments?.getBoolean(Routes.ARG_CATEGORY_ADD) ?: false
            CategoryScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                categoryUri = uri,
                autoOpenAddTodo = autoOpenAddTodo,
                initialAddText = backStackEntry.arguments?.getString(Routes.ARG_TEXT).orEmpty(),
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Routes.AddTask,
            arguments = listOf(
                navArgument(Routes.ARG_TEXT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val text = backStackEntry.arguments?.getString(Routes.ARG_TEXT)
            AddTaskScreen(
                fileRepository = fileRepository,
                onSelectCategory = { uri ->
                    navController.navigate(Routes.category(uri, addTodo = true, text = text))
                },
                onBack = {
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        KEY_REFRESH_HOME,
                        System.currentTimeMillis(),
                    )
                    navController.popBackStack()
                },
            )
        }

        composable(Routes.DailyFocus) {
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SavedView,
            arguments = listOf(
                navArgument(Routes.ARG_VIEW_FOLDER) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_VIEW_NAME) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            DailyFocusScreen(
                fileRepository = fileRepository,
                appSettingsRepository = appSettingsRepository,
                onBack = { navController.popBackStack() },
                viewFolder = backStackEntry.arguments?.getString(Routes.ARG_VIEW_FOLDER),
                viewName = backStackEntry.arguments?.getString(Routes.ARG_VIEW_NAME),
            )
        }
    }
}

private const val KEY_REFRESH_HOME = "refresh_home"

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Settings = "settings"
    const val DailyFocus = "daily_focus"
    const val ARG_TEXT = "text"
    const val AddTask = "add_task?$ARG_TEXT={$ARG_TEXT}"

    const val ARG_CATEGORY_URI = "categoryUri"
    const val ARG_CATEGORY_ADD = "add"
    const val Category = "category/{$ARG_CATEGORY_URI}?$ARG_CATEGORY_ADD={$ARG_CATEGORY_ADD}&$ARG_TEXT={$ARG_TEXT}"

    fun addTask(text: String? = null): String =
        if (text == null) "add_task" else "add_task?$ARG_TEXT=${Uri.encode(text)}"

    const val ARG_VIEW_FOLDER = "folder"
    const val ARG_VIEW_NAME = "name"
    const val SavedView = "view?$ARG_VIEW_FOLDER={$ARG_VIEW_FOLDER}&$ARG_VIEW_NAME={$ARG_VIEW_NAME}"

    fun savedView(folder: String, name: String): String =
        "view?$ARG_VIEW_FOLDER=${Uri.encode(folder)}&$ARG_VIEW_NAME=${Uri.encode(name)}"

    fun category(uri: Uri, addTodo: Boolean = false, text: String? = null): String =
        "category/${Uri.encode(uri.toString())}?$ARG_CATEGORY_ADD=$addTodo" +
            (text?.let { "&$ARG_TEXT=${Uri.encode(it)}" } ?: "")
}
