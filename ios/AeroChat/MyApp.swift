import SwiftUI

@main
struct MyApp: App {

    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(environment)
                .preferredColorScheme(.dark)
                .task {
                    await environment.bootstrap()
                }
        }
    }
}
