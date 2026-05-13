(function () {
  'use strict';

  const config = window.MAP_CONFIG || {};
  const state = {
    map: null,
    flowers: [],
    filtered: [],
    festivals: [],
    posts: [],
    currentPosition: null,
    radius: config.DEFAULT_RADIUS || 5000,
    search: '',
    kakaoReady: false,
    mapError: null,
    markers: [],
    festivalMarkers: [],
    postMarkers: [],
    // 길찾기 상태
    routePolyline: null,
    routeStartMarker: null,
    routeEndMarker: null,
    routeSteps: [],
  };

  const $ = (selector) => document.querySelector(selector);

  function init() {
    exposeFlutterBridge();
    bindKakaoEvents();
    initGeolocation();
    loadFlowers();
    loadFestivals();
  }

  function exposeFlutterBridge() {
    window.FlowerMap = {
      setSearchQuery: function (query) {
        state.search = String(query || '').trim();
        applyFilters();
      },
      zoomIn: function () { zoomMap(-1); },
      zoomOut: function () { zoomMap(1); },
      moveToCurrentLocation: function () { initGeolocation(); },
      // Flutter에서 위치를 직접 주입
      setCurrentPosition: function (lat, lng) {
        state.currentPosition = { lat: Number(lat), lng: Number(lng) };
        if (state.map && state.kakaoReady) {
          state.map.setCenter(new kakao.maps.LatLng(lat, lng));
        }
        loadFlowers();
      }
    };
  }

  function bindKakaoEvents() {
    document.addEventListener('kakao-map-ready', function () {
      state.kakaoReady = true;
      state.mapError = null;
      renderMap();
    });
    document.addEventListener('kakao-map-error', function () {
      state.kakaoReady = false;
      state.mapError = 'Kakao Maps SDK could not be loaded.';
      renderMap();
    });
  }

  // 테스트용 임시 마커 (실제 데이터 들어오면 제거) - 반경 필터 제외
  const TEST_MARKERS = [
    { flower_id: 't1', name: '여의도 벚꽃', species: '벚나무', address: '서울 영등포구 여의도동', location: { lat: 37.5219, lng: 126.9245 }, _test: true },
    { flower_id: 't2', name: '남산 개나리', species: '개나리', address: '서울 용산구 남산공원', location: { lat: 37.5512, lng: 126.9882 }, _test: true },
    { flower_id: 't3', name: '석촌호수 벚꽃', species: '벚나무', address: '서울 송파구 석촌호수', location: { lat: 37.5085, lng: 127.1020 }, _test: true },
  ];

  async function loadFlowers() {
    const apiFlowers = await fetchFlowersFromApi() || [];
    // API 데이터 없으면 테스트 마커 표시
    state.flowers = apiFlowers.length > 0 ? apiFlowers : TEST_MARKERS;
    applyFilters();
  }

  async function fetchFlowersFromApi() {
    const baseUrl = config.API_BASE_URL;
    if (!baseUrl) return [];
    try {
      const center = state.currentPosition || config.DEFAULT_CENTER || { lat: 37.5665, lng: 126.9780 };
      const params = new URLSearchParams({
        lat: center.lat, lng: center.lng,
        radius: state.radius, limit: config.DEFAULT_LIMIT || 50
      });
      const response = await fetch(`${baseUrl}/flowers?${params.toString()}`);
      if (!response.ok) return [];
      const body = await response.json();
      const data = Array.isArray(body) ? body : body.data;
      return normalizeFlowers(data || []);
    } catch (error) {
      console.warn('Flower API is unavailable.', error);
      return [];
    }
  }

  function normalizeFlowers(flowers) {
    return flowers.map(function (flower) {
      const lat = flower.location?.lat ?? flower.lat ?? flower.mapY;
      const lng = flower.location?.lng ?? flower.lng ?? flower.mapX;
      return {
        flower_id: flower.flower_id ?? flower.flowerId ?? flower.id,
        name: flower.name || '',
        species: flower.species || '',
        address: flower.address || '',
        location: { lat: Number(lat), lng: Number(lng) },
        distance_m: flower.distance_m ?? flower.distanceM
      };
    }).filter(function (flower) {
      return Number.isFinite(flower.location.lat) && Number.isFinite(flower.location.lng);
    });
  }

  async function loadFestivals() {
    const tourKey = config.TOUR_API_KEY;
    if (!tourKey) return;
    try {
      const now = new Date();
      // 3개월 전부터 검색 (지나간 축제 포함, 계절 축제 반영)
      const past = new Date(now);
      past.setMonth(past.getMonth() - 3);
      const eventStartDate = past.getFullYear() +
        String(past.getMonth() + 1).padStart(2, '0') +
        String(past.getDate()).padStart(2, '0');
      const params = new URLSearchParams({
        numOfRows: '100', pageNo: '1', MobileOS: 'ETC', MobileApp: 'FlowerApp',
        _type: 'json', eventStartDate: eventStartDate,
      });
      const url = 'https://apis.data.go.kr/B551011/KorService2/searchFestival2?serviceKey=' + tourKey + '&' + params.toString();
      const response = await fetch(url);
      if (!response.ok) return;
      const text = await response.text();
      if (text.trim().startsWith('<')) return;
      const data = JSON.parse(text);
      const items = data?.response?.body?.items?.item;
      const itemList = Array.isArray(items) ? items : items ? [items] : [];
      const flowerKeywords = /꽃|벚|진달래|튤립|장미|수국|국화|매화|개나리|철쭉|목련|코스모스|해바라기|라벤더|동백/;
      state.festivals = itemList
        .filter(function (item) {
          return Number(item.mapx || 0) !== 0 && Number(item.mapy || 0) !== 0
            && flowerKeywords.test(item.title || '');
        })
        .map(function (item) {
          return {
            title: item.title || '',
            mapX: Number(item.mapx),
            mapY: Number(item.mapy),
            imageUrl: item.firstimage || item.firstimage2 || '',
          };
        });
      renderMapMarkers();
    } catch (error) {
      console.warn('Festival API is unavailable.', error);
    }
  }

  function applyFilters() {
    const query = state.search.toLowerCase();
    const center = state.currentPosition;
    state.filtered = state.flowers.filter(function (flower) {
      if (query && !`${flower.name} ${flower.species} ${flower.address}`.toLowerCase().includes(query)) return false;
      if (center && !flower._test) {
        flower.distance_m = Math.round(distanceMeters(center.lat, center.lng, flower.location.lat, flower.location.lng));
        if (flower.distance_m > state.radius) return false;
      }
      return true;
    });
    renderMap();
  }

  function renderMap() {
    if (state.mapError) { showMapError(state.mapError); return; }
    if (!config.KAKAO_APP_KEY || config.KAKAO_APP_KEY === 'YOUR_KAKAO_JS_KEY_HERE') {
      showMapError('Kakao Maps JavaScript key is not configured.');
      return;
    }
    if (state.kakaoReady && window.kakao?.maps) {
      if (renderKakaoMap()) return;
      state.kakaoReady = false;
      state.map = null;
      clearKakaoMarkers();
      showMapError('Kakao map could not be rendered.');
      return;
    }
    showMapStatus('Loading Kakao map...');
  }

  function renderKakaoMap() {
    try {
      if (!state.map) {
        const center = config.DEFAULT_CENTER || { lat: 37.5665, lng: 126.9780 };
        state.map = new kakao.maps.Map($('#map'), {
          center: new kakao.maps.LatLng(center.lat, center.lng),
          level: config.DEFAULT_ZOOM_LEVEL || 5
        });
      }
      renderMapMarkers();
      return true;
    } catch (error) {
      console.error('Kakao Maps could not be rendered.', error);
      return false;
    }
  }

  function renderMapMarkers() {
    if (!state.map || !window.kakao?.maps) return;
    clearKakaoMarkers();

    state.filtered.forEach(function (flower) {
      const position = new kakao.maps.LatLng(flower.location.lat, flower.location.lng);
      const marker = new kakao.maps.Marker({ position: position });
      marker.setMap(state.map);
      kakao.maps.event.addListener(marker, 'click', function () {
        showMarkerInfo(flower.name || flower.species || '꽃', flower.address || '',
          flower.location.lat, flower.location.lng);
      });
      state.markers.push(marker);
    });

    state.festivals.forEach(function (festival) {
      const position = new kakao.maps.LatLng(festival.mapY, festival.mapX);
      const marker = new kakao.maps.Marker({ position: position });
      marker.setMap(state.map);
      kakao.maps.event.addListener(marker, 'click', function () {
        showMarkerInfo(festival.title || '축제', '', festival.mapY, festival.mapX);
      });
      state.festivalMarkers.push(marker);
    });
  }

  function clearKakaoMarkers() {
    state.markers.forEach(function (m) { m.setMap(null); });
    state.markers = [];
    state.festivalMarkers.forEach(function (m) { m.setMap(null); });
    state.festivalMarkers = [];
    state.postMarkers.forEach(function (m) { m.setMap(null); });
    state.postMarkers = [];
  }

  function zoomMap(delta) {
    if (!state.map || !state.kakaoReady || !window.kakao?.maps) return;
    const nextLevel = Math.max(1, Math.min(14, state.map.getLevel() + delta));
    state.map.setLevel(nextLevel);
  }

  function initGeolocation() {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(function (position) {
      state.currentPosition = { lat: position.coords.latitude, lng: position.coords.longitude };
      loadFlowers();
    }, function () {
      state.currentPosition = null;
      applyFilters();
    }, { enableHighAccuracy: true, timeout: 8000 });
  }

  // ── 마커 정보 패널 ───────────────────────────────────────────────

  function showMarkerInfo(name, address, lat, lng) {
    var old = document.getElementById('marker-info');
    if (old) old.remove();
    var panel = document.createElement('div');
    panel.id = 'marker-info';
    panel.className = 'route-panel';
    panel.innerHTML =
      '<div class="route-panel-row">'
      + '<span class="route-icon">🌸</span>'
      + '<div class="route-info">'
      + '  <strong>' + escapeHtml(name) + '</strong>'
      + (address ? '<span>' + escapeHtml(address) + '</span>' : '')
      + '</div>'
      + '<button id="btn-navigate" class="route-toggle" style="width:auto;padding:0 10px;border-radius:12px;font-size:12px;">길찾기</button>'
      + '<button id="btn-close-info" class="route-close">✕</button>'
      + '</div>';
    document.getElementById('map-shell').appendChild(panel);
    document.getElementById('btn-close-info').addEventListener('click', function () {
      panel.remove();
    });
    document.getElementById('btn-navigate').addEventListener('click', function () {
      panel.remove();
      navigateInApp(lat, lng, name);
    });
  }

  // ── 길찾기 (OSRM) ─────────────────────────────────────────────

  async function navigateInApp(destLat, destLng, destName) {
    if (!state.currentPosition) {
      try {
        await new Promise(function (resolve, reject) {
          if (!navigator.geolocation) return reject(new Error('GPS 미지원'));
          navigator.geolocation.getCurrentPosition(
            function (pos) {
              state.currentPosition = { lat: pos.coords.latitude, lng: pos.coords.longitude };
              resolve();
            },
            function () { reject(new Error('위치 권한 거부')); },
            { enableHighAccuracy: true, timeout: 8000 }
          );
        });
      } catch (e) {
        showRouteError('현재 위치를 확인할 수 없습니다. GPS를 켜주세요.');
        return;
      }
    }
    showRouteLoading(destName);
    try {
      var route = await fetchWalkingRoute(
        state.currentPosition.lat, state.currentPosition.lng, destLat, destLng
      );
      drawRouteOnMap(route.coordinates, state.currentPosition, { lat: destLat, lng: destLng });
      state.routeSteps = route.steps || [];
      showRoutePanel(route.distance, route.duration, destName, route.steps || []);
    } catch (e) {
      showRouteError('경로를 찾을 수 없습니다.');
    }
  }

  async function fetchWalkingRoute(startLat, startLng, endLat, endLng) {
    var url = 'https://router.project-osrm.org/route/v1/foot/'
      + startLng + ',' + startLat + ';' + endLng + ',' + endLat
      + '?overview=full&geometries=geojson&steps=true';
    var res = await fetch(url);
    if (!res.ok) throw new Error('API ' + res.status);
    var data = await res.json();
    if (!data.routes || !data.routes.length) throw new Error('경로 없음');
    var route = data.routes[0];
    var steps = [];
    if (route.legs) {
      route.legs.forEach(function (leg) {
        if (leg.steps) {
          leg.steps.forEach(function (step) {
            if (step.maneuver && step.distance > 0) {
              steps.push({
                instruction: translateManeuver(step.maneuver.type, step.maneuver.modifier, step.name),
                distance: step.distance,
                duration: step.duration,
              });
            }
          });
        }
      });
    }
    return { coordinates: route.geometry.coordinates, distance: route.distance, duration: route.duration, steps: steps };
  }

  function translateManeuver(type, modifier, name) {
    var road = name ? ' (' + name + ')' : '';
    var map = {
      'depart': '출발', 'arrive': '도착',
      'turn-left': '좌회전', 'turn-right': '우회전',
      'turn-slight left': '살짝 좌회전', 'turn-slight right': '살짝 우회전',
      'turn-sharp left': '크게 좌회전', 'turn-sharp right': '크게 우회전',
      'continue-straight': '직진', 'continue-': '직진',
      'fork-left': '왼쪽 갈림길', 'fork-right': '오른쪽 갈림길',
      'roundabout-': '로타리 진입', 'new name-': '길을 따라 이동'
    };
    var key = type + '-' + (modifier || '');
    var label = map[key] || map[type + '-'] || type + (modifier ? ' ' + modifier : '');
    return label + road;
  }

  function drawRouteOnMap(geojsonCoords, startPos, endPos) {
    clearRoute();
    if (!state.map || !state.kakaoReady) return;
    var path = geojsonCoords.map(function (c) { return new kakao.maps.LatLng(c[1], c[0]); });
    state.routePolyline = new kakao.maps.Polyline({
      path: path, strokeWeight: 7, strokeColor: '#4285F4', strokeOpacity: 0.9, strokeStyle: 'solid'
    });
    state.routePolyline.setMap(state.map);
    try {
      var startSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">'
        + '<circle cx="16" cy="16" r="13" fill="#4285F4" stroke="white" stroke-width="3"/>'
        + '<circle cx="16" cy="16" r="5" fill="white"/></svg>';
      state.routeStartMarker = new kakao.maps.Marker({
        position: new kakao.maps.LatLng(startPos.lat, startPos.lng),
        image: new kakao.maps.MarkerImage('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(startSvg),
          new kakao.maps.Size(32, 32), { offset: new kakao.maps.Point(16, 16) })
      });
      state.routeStartMarker.setMap(state.map);
      var endSvg = '<svg xmlns="http://www.w3.org/2000/svg" width="36" height="44" viewBox="0 0 36 44">'
        + '<path d="M18 0C8.06 0 0 8.06 0 18c0 12.6 18 26 18 26s18-13.4 18-26C36 8.06 27.94 0 18 0z" fill="#EA4335"/>'
        + '<circle cx="18" cy="16" r="7" fill="white"/></svg>';
      state.routeEndMarker = new kakao.maps.Marker({
        position: new kakao.maps.LatLng(endPos.lat, endPos.lng),
        image: new kakao.maps.MarkerImage('data:image/svg+xml;charset=utf-8,' + encodeURIComponent(endSvg),
          new kakao.maps.Size(36, 44), { offset: new kakao.maps.Point(18, 44) })
      });
      state.routeEndMarker.setMap(state.map);
    } catch (e) { /* ignore */ }
    var bounds = new kakao.maps.LatLngBounds();
    path.forEach(function (p) { bounds.extend(p); });
    state.map.setBounds(bounds);
  }

  function clearRoute() {
    if (state.routePolyline) { state.routePolyline.setMap(null); state.routePolyline = null; }
    if (state.routeStartMarker) { state.routeStartMarker.setMap(null); state.routeStartMarker = null; }
    if (state.routeEndMarker) { state.routeEndMarker.setMap(null); state.routeEndMarker = null; }
    state.routeSteps = [];
    var panel = document.getElementById('route-panel');
    if (panel) panel.remove();
  }

  function showRoutePanel(distM, durS, destName, steps) {
    var old = document.getElementById('route-panel');
    if (old) old.remove();
    var dist = distM >= 1000 ? (distM / 1000).toFixed(1) + ' km' : Math.round(distM) + ' m';
    var mins = Math.ceil(durS / 60);
    var time = mins >= 60 ? Math.floor(mins / 60) + '시간 ' + (mins % 60) + '분' : mins + '분';
    var panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'route-panel';
    var html = '<div class="route-panel-row">'
      + '<span class="route-icon">🚶</span>'
      + '<div class="route-info"><strong>' + escapeHtml(destName) + '</strong>'
      + '<span>📏 ' + dist + ' &nbsp; ⏱ 도보 ' + time + '</span></div>'
      + (steps && steps.length ? '<button id="btn-toggle-steps" class="route-toggle">▼</button>' : '')
      + '<button id="btn-close-route" class="route-close">✕</button></div>';
    if (steps && steps.length) {
      html += '<div id="route-steps" class="route-steps" style="display:none;">';
      steps.forEach(function (s) {
        var sDist = s.distance >= 1000 ? (s.distance / 1000).toFixed(1) + 'km' : Math.round(s.distance) + 'm';
        html += '<div class="route-step"><span class="step-num">' + getStepIcon(s.instruction) + '</span>'
          + '<span class="step-text">' + escapeHtml(s.instruction) + '</span>'
          + '<span class="step-dist">' + sDist + '</span></div>';
      });
      html += '</div>';
    }
    panel.innerHTML = html;
    document.getElementById('map-shell').appendChild(panel);
    document.getElementById('btn-close-route').addEventListener('click', clearRoute);
    var toggleBtn = document.getElementById('btn-toggle-steps');
    if (toggleBtn) {
      toggleBtn.addEventListener('click', function () {
        var el = document.getElementById('route-steps');
        if (!el) return;
        var hidden = el.style.display === 'none';
        el.style.display = hidden ? 'block' : 'none';
        toggleBtn.textContent = hidden ? '▲' : '▼';
      });
    }
  }

  function getStepIcon(instruction) {
    if (instruction.indexOf('좌회전') >= 0) return '↰';
    if (instruction.indexOf('우회전') >= 0) return '↱';
    if (instruction.indexOf('직진') >= 0) return '↑';
    if (instruction.indexOf('출발') >= 0) return '🏁';
    if (instruction.indexOf('도착') >= 0) return '📍';
    if (instruction.indexOf('갈림길') >= 0) return '⑂';
    if (instruction.indexOf('로타리') >= 0) return '↻';
    return '→';
  }

  function showRouteLoading(destName) {
    var old = document.getElementById('route-panel');
    if (old) old.remove();
    var panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'route-panel';
    panel.innerHTML = '<div class="route-panel-row"><span class="route-icon">⏳</span>'
      + '<div class="route-info"><strong>' + escapeHtml(destName) + '</strong>'
      + '<span>경로 검색 중...</span></div></div>';
    document.getElementById('map-shell').appendChild(panel);
  }

  function showRouteError(msg) {
    var old = document.getElementById('route-panel');
    if (old) old.remove();
    var panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'route-panel route-error';
    panel.innerHTML = '<div class="route-panel-row"><span class="route-icon">⚠️</span>'
      + '<div class="route-info"><span>' + escapeHtml(msg) + '</span></div>'
      + '<button id="btn-close-route" class="route-close">✕</button></div>';
    document.getElementById('map-shell').appendChild(panel);
    document.getElementById('btn-close-route').addEventListener('click', clearRoute);
  }

  // ── 유틸 ──────────────────────────────────────────────────────

  function showMapStatus(message) {
    $('#map').innerHTML = `<div class="map-message">${escapeHtml(message)}</div>`;
  }

  function showMapError(message) {
    $('#map').innerHTML = `<div class="map-message error">${escapeHtml(message)}</div>`;
  }

  function distanceMeters(lat1, lng1, lat2, lng2) {
    const R = 6371000;
    const dLat = toRadians(lat2 - lat1);
    const dLng = toRadians(lng2 - lng1);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function toRadians(v) { return v * Math.PI / 180; }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[c];
    });
  }

  document.addEventListener('DOMContentLoaded', init);
})();
