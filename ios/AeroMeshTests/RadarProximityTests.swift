import Foundation
import Testing
@testable import AeroChat

/// Radar di prossimita': filtro dell'RSSI, stima della distanza e RADAR_PING.
struct RadarProximityTests {

    @Test func rssiFilterSmoothsNoiseAndDampsSpikes() {
        var filter = RSSIFilter()
        let start = Date()
        // Letture rumorose attorno a -70 dBm, una al secondo.
        let noisy = [-70, -64, -76, -68, -73, -66, -74, -69, -71, -67]
        var last = 0
        for (index, rssi) in noisy.enumerated() {
            last = filter.add(rssi, at: start.addingTimeInterval(Double(index)))
        }
        #expect(abs(last - (-70)) <= 3)
        // Un picco isolato sposta la stima di poco.
        let afterSpike = filter.add(-45, at: start.addingTimeInterval(10))
        #expect(afterSpike < last + 6)
        // Uno spostamento reale e duraturo viene seguito.
        var moved = afterSpike
        for second in 11...25 {
            moved = filter.add(-85, at: start.addingTimeInterval(Double(second)))
        }
        #expect(moved <= -80)
    }

    @Test func distanceModelIsMonotonicAndCalibratedAtOneMeter() {
        #expect(abs(ProximityEstimator.meters(rssi: -59) - 1) < 0.01)
        #expect(ProximityEstimator.meters(rssi: -70) < ProximityEstimator.meters(rssi: -80))
        #expect(ProximityEstimator.meters(rssi: -20) == 0.3)
        #expect(ProximityEstimator.meters(rssi: -127) == 60)
        // UWB vince sull'RSSI.
        let uwb = UWBMeasurement(meters: 1.4, azimuth: nil, updatedAt: Date())
        #expect(ProximityEstimator.meters(rssi: -90, uwb: uwb) == 1.4)
        #expect(ProximityEstimator.meters(rssi: nil, uwb: nil) == nil)
    }

    @Test func radarRadiusIsLogarithmicWithRingsInsideBounds() {
        #expect(ProximityEstimator.radiusFraction(meters: nil) == 1)
        #expect(abs(ProximityEstimator.radiusFraction(meters: 0.1) - 0.1) < 0.001)
        #expect(abs(ProximityEstimator.radiusFraction(meters: 100) - 1) < 0.001)
        let rings = ProximityEstimator.ringMeters.map { ProximityEstimator.radiusFraction(meters: $0) }
        #expect(rings == rings.sorted())
        #expect(rings.allSatisfy { $0 > 0.1 && $0 <= 1 })
    }

    @Test func radarPingRoundTripsWithNegativeRSSI() throws {
        var ping = RadarPing()
        ping.targetNodeID = Data(repeating: 7, count: 8)
        ping.rssiDbm = -67
        ping.niDiscoveryToken = Data([1, 2, 3])
        ping.isActive = true
        let decoded = try MeshWire.parseRadarPing(try MeshWire.serialize(ping))
        #expect(decoded == ping)
        #expect(PacketType(rawValue: 19) == .radarPing)
        #expect(!PacketType.radarPing.isStoreAndForwardEligible)
    }

    @Test func radarPingReachesOnlyTheDirectNeighbor() async throws {
        let alice = try await TestNode.make("Alice")
        let bob = try await TestNode.make("Bob")
        let events = await bob.router.events()
        await FakeMeshNetwork.connect(alice.transport, bob.transport)
        #expect(await eventually { await alice.router.isDirectNeighbor(bob.nodeID) })
        let token = Data([9, 8, 7])
        let received = Task { () -> (Data, Data?, Bool)? in
            for await event in events {
                if case .radarPingReceived(let nodeID, let discoveryToken, let isActive) = event {
                    return (nodeID, discoveryToken, isActive)
                }
            }
            return nil
        }
        #expect(await alice.router.sendRadarPing(to: bob.nodeID, discoveryToken: token, isActive: true))
        let ping = try #require(await received.value)
        #expect(ping.0 == alice.nodeID)
        #expect(ping.1 == token)
        #expect(ping.2)
        // Nessun ping verso chi non e' un vicino diretto.
        #expect(await alice.router.sendRadarPing(to: Data(repeating: 1, count: 8), discoveryToken: nil, isActive: true) == false)
    }
}
