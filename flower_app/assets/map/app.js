(function () {
  'use strict';

  const config = window.MAP_CONFIG || {};
  const DEFAULT_FALLBACK_POSITION = { lat: 37.5665, lng: 126.9780 };
  const state = {
    map: null,
    flowers: [],
    filteredFlowers: [],
    festivals: [],
    currentPosition: null,
    radius: config.DEFAULT_RADIUS || 5000,
    search: '',
    kakaoReady: false,
    mapError: null,
    markers: [],
    festivalMarkers: [],
    routePolyline: null,
    routeStartMarker: null,
    routeEndMarker: null,
    routeSteps: [],
    showFlowers: true,
    showFestivals: true,
    mapInteractionBound: false,
  };

  const FLOWER_KEYWORDS = [
    '\uAF43',
    '\uBC9A\uAF43',
    '\uC9C4\uB2EC\uB798',
    '\uB9E4\uD654',
    '\uC218\uAD6D',
    '\uC7A5\uBBF8',
    '\uAC1C\uB098\uB9AC',
    '\uCCA0\uCB49',
    '\uAD6D\uD654',
    '\uD574\uBC14\uB77C\uAE30',
    '\uCF54\uC2A4\uBAA8\uC2A4',
    '\uB3D9\uBC31',
    '\uC720\uCC44',
    'flower',
  ];

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
      setSearchQuery(query) {
        state.search = String(query || '').trim();
        applyFilters();
      },
      zoomIn() {
        zoomMap(-1);
      },
      zoomOut() {
        zoomMap(1);
      },
      moveToCurrentLocation() {
        initGeolocation();
      },
      setCurrentPosition(lat, lng) {
        state.currentPosition = { lat: Number(lat), lng: Number(lng) };
        if (state.map && state.kakaoReady) {
          state.map.setCenter(new kakao.maps.LatLng(lat, lng));
        }
        applyFilters();
      },
      setVisibleCategories(flowers, festivals) {
        state.showFlowers = !!flowers;
        state.showFestivals = !!festivals;
        renderMapMarkers();
      },
      setFestivals(festivals) {
        try {
          const parsed = typeof festivals === 'string'
            ? JSON.parse(festivals)
            : festivals;
          state.festivals = normalizeFestivalList(parsed);
          renderMapMarkers();
        } catch (error) {
          console.warn('Failed to set festivals from Flutter.', error);
        }
      },
      focusFestival(festival) {
        try {
          const parsed = typeof festival === 'string'
            ? JSON.parse(festival)
            : festival;
          if (parsed) {
            focusFestival(normalizeFestival(parsed));
          }
        } catch (error) {
          console.warn('Failed to focus festival.', error);
        }
      },
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
      state.mapError = '카카오 지도 SDK를 불러오지 못했습니다.';
      renderMap();
    });
  }

  async function loadFlowers() {
    const apiFlowers = await fetchFlowersFromApi();
    state.flowers = apiFlowers;
    applyFilters();
  }

  async function fetchFlowersFromApi() {
    const baseUrl = config.API_BASE_URL;
    if (!baseUrl) return [];

    try {
      const center = state.currentPosition ||
        config.DEFAULT_CENTER ||
        { lat: 37.5665, lng: 126.9780 };
      const params = new URLSearchParams({
        lat: center.lat,
        lng: center.lng,
        radius: state.radius,
        limit: config.DEFAULT_LIMIT || 50,
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
    return flowers
      .map(function (flower) {
        const lat = flower.location?.lat ?? flower.lat ?? flower.mapY;
        const lng = flower.location?.lng ?? flower.lng ?? flower.mapX;
        return {
          flower_id: flower.flower_id ?? flower.flowerId ?? flower.id,
          name: flower.name || '',
          species: flower.species || '',
          address: flower.address || '',
          location: { lat: Number(lat), lng: Number(lng) },
          distance_m: flower.distance_m ?? flower.distanceM,
        };
      })
      .filter(function (flower) {
        return Number.isFinite(flower.location.lat) &&
          Number.isFinite(flower.location.lng);
      });
  }

  async function loadFestivals() {
    const tourKey = config.TOUR_API_KEY;
    if (!tourKey) return;

    try {
      const now = new Date();
      const past = new Date(now);
      past.setMonth(past.getMonth() - 3);
      const eventStartDate = past.getFullYear() +
        String(past.getMonth() + 1).padStart(2, '0') +
        String(past.getDate()).padStart(2, '0');

      const params = new URLSearchParams({
        serviceKey: tourKey,
        numOfRows: '80',
        pageNo: '1',
        MobileOS: 'ETC',
        MobileApp: 'FlowerApp',
        _type: 'json',
        eventStartDate: eventStartDate,
        contentTypeId: '15',
      });

      const response = await fetch(
        `https://apis.data.go.kr/B551011/KorService2/searchFestival2?${params.toString()}`,
      );
      if (!response.ok) return;
      const data = await response.json();
      const items = data?.response?.body?.items?.item;
      const itemList = Array.isArray(items) ? items : items ? [items] : [];
      state.festivals = normalizeFestivalList(itemList);
      renderMapMarkers();
    } catch (error) {
      console.warn('Festival API is unavailable.', error);
    }
  }

  function normalizeFestivalList(list) {
    return (Array.isArray(list) ? list : [])
      .map(normalizeFestival)
      .filter(Boolean);
  }

  function normalizeFestival(item) {
    if (!item) return null;

    const festival = {
      contentId: item.contentId || item.contentid || '',
      title: item.title || '',
      address: item.address || [item.addr1 || '', item.addr2 || ''].join(' ').trim(),
      imageUrl: item.imageUrl || item.firstimage || item.firstimage2 || '',
      period: item.period || formatPeriod(
        item.eventStartDate || item.eventstartdate || '',
        item.eventEndDate || item.eventenddate || '',
      ),
      mapX: Number(item.mapX ?? item.mapx ?? item.lng ?? item.longitude ?? 0),
      mapY: Number(item.mapY ?? item.mapy ?? item.lat ?? item.latitude ?? 0),
    };

    if (!festival.title) return null;
    if (!Number.isFinite(festival.mapX) ||
        !Number.isFinite(festival.mapY) ||
        festival.mapX === 0 ||
        festival.mapY === 0) {
      return null;
    }

    if (!isFlowerFestival(festival.title)) return null;
    return festival;
  }

  function isFlowerFestival(title) {
    const text = String(title || '').toLowerCase();
    return FLOWER_KEYWORDS.some(function (keyword) {
      return text.includes(String(keyword).toLowerCase());
    });
  }

  function formatPeriod(start, end) {
    if (!start) return '';
    const normalizedStart = formatCompactDate(start);
    const normalizedEnd = formatCompactDate(end);
    return normalizedEnd ? `${normalizedStart} - ${normalizedEnd}` : normalizedStart;
  }

  function formatCompactDate(value) {
    const text = String(value || '');
    if (text.length !== 8) return text;
    return `${text.substring(0, 4)}.${text.substring(4, 6)}.${text.substring(6, 8)}`;
  }

  function applyFilters() {
    const query = state.search.toLowerCase();
    const center = state.currentPosition;

    state.filteredFlowers = state.flowers.filter(function (flower) {
      if (query &&
          !`${flower.name} ${flower.species} ${flower.address}`
            .toLowerCase()
            .includes(query)) {
        return false;
      }

      if (center && !flower._test) {
        flower.distance_m = Math.round(
          distanceMeters(
            center.lat,
            center.lng,
            flower.location.lat,
            flower.location.lng,
          ),
        );
        if (flower.distance_m > state.radius) return false;
      }

      return true;
    });

    renderMap();
  }

  function renderMap() {
    if (state.mapError) {
      showMapError(state.mapError);
      return;
    }

    if (!config.KAKAO_APP_KEY ||
        config.KAKAO_APP_KEY === 'YOUR_KAKAO_JS_KEY_HERE') {
      showMapError('카카오 지도 JavaScript 키가 설정되지 않았습니다.');
      return;
    }

    if (state.kakaoReady && window.kakao?.maps) {
      if (renderKakaoMap()) return;
      state.kakaoReady = false;
      state.map = null;
      clearKakaoMarkers();
      showMapError('카카오 지도를 표시하지 못했습니다.');
      return;
    }

    showMapStatus('지도를 불러오는 중입니다...');
  }

  function renderKakaoMap() {
    try {
      if (!state.map) {
        const center = config.DEFAULT_CENTER || { lat: 37.5665, lng: 126.9780 };
        state.map = new kakao.maps.Map($('#map'), {
          center: new kakao.maps.LatLng(center.lat, center.lng),
          level: config.DEFAULT_ZOOM_LEVEL || 5,
        });
      }
      bindMapInteractions();
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

    if (state.showFlowers) {
      state.filteredFlowers.forEach(function (flower) {
        const position = new kakao.maps.LatLng(
          flower.location.lat,
          flower.location.lng,
        );
        const marker = new kakao.maps.Marker({ position: position });
        marker.setMap(state.map);
        kakao.maps.event.addListener(marker, 'click', function () {
          showMarkerInfo({
            name: flower.name || flower.species || '꽃 명소',
            address: flower.address || '',
            lat: flower.location.lat,
            lng: flower.location.lng,
            isFestival: false,
          });
        });
        state.markers.push(marker);
      });
    }

    if (state.showFestivals) {
      state.festivals.forEach(function (festival) {
        const position = new kakao.maps.LatLng(festival.mapY, festival.mapX);
        const marker = new kakao.maps.Marker({
          position: position,
          image: buildFestivalMarkerImage(),
        });
        marker.setMap(state.map);
        kakao.maps.event.addListener(marker, 'click', function () {
          showMarkerInfo({
            name: festival.title || '축제',
            address: festival.address || '',
            lat: festival.mapY,
            lng: festival.mapX,
            isFestival: true,
            imageUrl: festival.imageUrl || '',
            period: festival.period || '',
            festival: festival,
          });
        });
        state.festivalMarkers.push(marker);
      });
    }
  }

  function buildFestivalMarkerImage() {
    const svg = [
      '<svg xmlns="http://www.w3.org/2000/svg" width="34" height="42" viewBox="0 0 34 42">',
      '<path d="M17 0C7.61 0 0 7.61 0 17c0 11.8 17 25 17 25s17-13.2 17-25C34 7.61 26.39 0 17 0z" fill="#ff4fa3"/>',
      '<circle cx="17" cy="16" r="7" fill="white"/>',
      '<path d="M17 11l1.8 3.8 4.2.5-3.1 2.9.8 4.2-3.7-2-3.7 2 .8-4.2-3.1-2.9 4.2-.5z" fill="#ff4fa3"/>',
      '</svg>',
    ].join('');

    return new kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
      new kakao.maps.Size(34, 42),
      { offset: new kakao.maps.Point(17, 42) },
    );
  }

  function clearKakaoMarkers() {
    state.markers.forEach(function (marker) {
      marker.setMap(null);
    });
    state.markers = [];

    state.festivalMarkers.forEach(function (marker) {
      marker.setMap(null);
    });
    state.festivalMarkers = [];
  }

  function zoomMap(delta) {
    if (!state.map || !state.kakaoReady || !window.kakao?.maps) return;
    const nextLevel = Math.max(1, Math.min(14, state.map.getLevel() + delta));
    state.map.setLevel(nextLevel);
  }

  function initGeolocation() {
    if (!navigator.geolocation) return;

    navigator.geolocation.getCurrentPosition(
      function (position) {
        state.currentPosition = {
          lat: position.coords.latitude,
          lng: position.coords.longitude,
        };
        if (state.map) {
          state.map.setCenter(
            new kakao.maps.LatLng(
              state.currentPosition.lat,
              state.currentPosition.lng,
            ),
          );
        }
        applyFilters();
      },
      function () {
        applyFilters();
      },
      { enableHighAccuracy: true, timeout: 8000 },
    );
  }

  function focusFestival(festival) {
    if (!festival || !state.map || !window.kakao?.maps) return;
    const position = new kakao.maps.LatLng(festival.mapY, festival.mapX);
    state.map.setLevel(Math.min(state.map.getLevel(), 5));
    panToMarkerWithOffset(position, 170);

    showMarkerInfo({
      name: festival.title || '축제',
      address: festival.address || '',
      lat: festival.mapY,
      lng: festival.mapX,
      isFestival: true,
      imageUrl: festival.imageUrl || '',
      period: festival.period || '',
      festival: festival,
    });
  }

  function bindMapInteractions() {
    if (!state.map || !window.kakao?.maps || state.mapInteractionBound) return;
    kakao.maps.event.addListener(state.map, 'click', function () {
      dismissTransientUi();
    });
    state.mapInteractionBound = true;
  }

  function panToMarkerWithOffset(position, offsetY) {
    if (!state.map || !window.kakao?.maps) return;

    try {
      const projection = state.map.getProjection();
      const point = projection.containerPointFromCoords(position);
      const nextPoint = new kakao.maps.Point(point.x, point.y + offsetY);
      const nextCenter = projection.coordsFromContainerPoint(nextPoint);
      state.map.panTo(nextCenter);
    } catch (_) {
      state.map.panTo(position);
    }
  }

  function showMarkerInfo(item) {
    dismissTransientUi();

    const panel = document.createElement('div');
    panel.id = 'marker-info';
    panel.className = 'map-panel marker-panel';

    const meta = [item.period, item.address].filter(Boolean).join(' · ');
    panel.innerHTML = [
      '<div class="panel-row">',
      '<div class="panel-info">',
      `<strong>${escapeHtml(item.name || '')}</strong>`,
      meta ? `<span>${escapeHtml(meta)}</span>` : '',
      '</div>',
      '<button class="panel-action" data-role="navigate">길찾기</button>',
      item.isFestival
        ? '<button class="panel-action ghost" data-role="detail">정보</button>'
        : '',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);

    panel.querySelector('[data-role="navigate"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제');
    });

    const detailButton = panel.querySelector('[data-role="detail"]');
    if (detailButton) {
      detailButton.addEventListener('click', function () {
        showFestivalDetail(item.festival || item);
      });
    }
  }

  async function showFestivalDetail(festival) {
    dismissTransientUi();

    const enrichedFestival = await enrichFestivalDetail(festival);

    const panel = document.createElement('section');
    panel.id = 'festival-detail';
    panel.className = 'map-panel detail-panel';

    const imageMarkup = enrichedFestival.imageUrl
      ? `<img src="${escapeAttribute(enrichedFestival.imageUrl)}" alt="${escapeAttribute(enrichedFestival.title || '축제 이미지')}">`
      : '<div class="detail-placeholder">꽃</div>';

    panel.innerHTML = [
      '<div class="detail-hero">', imageMarkup, '</div>',
      '<div class="detail-content">',
      '<div class="detail-header">',
      `<strong>${escapeHtml(enrichedFestival.title || '축제')}</strong>`,
      '</div>',
      enrichedFestival.period ? `<p class="detail-meta">기간: ${escapeHtml(enrichedFestival.period)}</p>` : '',
      enrichedFestival.eventPlace ? `<p class="detail-body">장소: ${escapeHtml(enrichedFestival.eventPlace)}</p>` : '',
      enrichedFestival.address ? `<p class="detail-body">주소: ${escapeHtml(enrichedFestival.address)}</p>` : '',
      enrichedFestival.useTimeFestival ? `<p class="detail-body">이용안내: ${escapeHtml(enrichedFestival.useTimeFestival)}</p>` : '',
      enrichedFestival.playTime ? `<p class="detail-body">운영시간: ${escapeHtml(enrichedFestival.playTime)}</p>` : '',
      enrichedFestival.homepage ? `<p class="detail-body">홈페이지: ${escapeHtml(stripHtml(enrichedFestival.homepage))}</p>` : '',
      enrichedFestival.tel ? `<p class="detail-body">문의: ${escapeHtml(enrichedFestival.tel)}</p>` : '',
      enrichedFestival.overview ? `<p class="detail-body">${escapeHtml(stripHtml(enrichedFestival.overview))}</p>` : '',
      !enrichedFestival.period &&
        !enrichedFestival.eventPlace &&
        !enrichedFestival.address &&
        !enrichedFestival.useTimeFestival &&
        !enrichedFestival.playTime &&
        !enrichedFestival.homepage &&
        !enrichedFestival.tel &&
        !enrichedFestival.overview
        ? '<p class="detail-body">추가로 표시할 상세 정보가 없습니다.</p>'
        : '',
      '<div class="detail-actions">',
      '<button class="detail-button primary" data-role="navigate">길찾기</button>',
      '</div>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);

    panel.querySelector('[data-role="navigate"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(
        enrichedFestival.mapY,
        enrichedFestival.mapX,
        enrichedFestival.title || '축제',
      );
    });
  }

  async function enrichFestivalDetail(festival) {
    if (!festival?.contentId || !config.TOUR_API_KEY) {
      return festival;
    }

    try {
      const [common, intro] = await Promise.all([
        fetchFestivalCommonDetail(festival.contentId),
        fetchFestivalIntroDetail(festival.contentId),
      ]);

      return {
        ...festival,
        imageUrl: common.imageUrl || festival.imageUrl || '',
        address: common.address || festival.address || '',
        tel: common.tel || intro.sponsorTel || festival.tel || '',
        homepage: common.homepage || '',
        overview: common.overview || '',
        eventPlace: intro.eventPlace || '',
        playTime: intro.playTime || '',
        useTimeFestival: intro.useTimeFestival || '',
      };
    } catch (error) {
      console.warn('축제 상세 정보를 가져오지 못했습니다.', error);
      return festival;
    }
  }

  async function fetchFestivalCommonDetail(contentId) {
    const params = new URLSearchParams({
      serviceKey: config.TOUR_API_KEY,
      MobileOS: 'ETC',
      MobileApp: 'FlowerApp',
      _type: 'json',
      contentId: String(contentId),
      defaultYN: 'Y',
      firstImageYN: 'Y',
      addrinfoYN: 'Y',
      mapinfoYN: 'Y',
      overviewYN: 'Y',
    });

    const response = await fetch(
      `https://apis.data.go.kr/B551011/KorService2/detailCommon2?${params.toString()}`,
    );
    if (!response.ok) throw new Error(`detailCommon2 ${response.status}`);
    const data = await response.json();
    const item = normalizeDetailItem(data);

    return {
      imageUrl: item.firstimage || item.firstimage2 || '',
      address: [item.addr1 || '', item.addr2 || ''].join(' ').trim(),
      tel: item.tel || '',
      homepage: item.homepage || '',
      overview: item.overview || '',
    };
  }

  async function fetchFestivalIntroDetail(contentId) {
    const params = new URLSearchParams({
      serviceKey: config.TOUR_API_KEY,
      MobileOS: 'ETC',
      MobileApp: 'FlowerApp',
      _type: 'json',
      contentId: String(contentId),
      contentTypeId: '15',
    });

    const response = await fetch(
      `https://apis.data.go.kr/B551011/KorService2/detailIntro2?${params.toString()}`,
    );
    if (!response.ok) throw new Error(`detailIntro2 ${response.status}`);
    const data = await response.json();
    const item = normalizeDetailItem(data);

    return {
      eventPlace: item.eventplace || '',
      playTime: item.playtime || '',
      useTimeFestival: item.usetimefestival || '',
      sponsorTel: item.sponsor1tel || item.sponsor2tel || '',
    };
  }

  function normalizeDetailItem(data) {
    const item = data?.response?.body?.items?.item;
    if (Array.isArray(item)) return item[0] || {};
    return item || {};
  }

  async function navigateInApp(destLat, destLng, destName) {
    if (!state.currentPosition) {
      try {
        await new Promise(function (resolve, reject) {
          if (!navigator.geolocation) {
            state.currentPosition = DEFAULT_FALLBACK_POSITION;
            resolve();
            return;
          }

          navigator.geolocation.getCurrentPosition(
            function (position) {
              state.currentPosition = {
                lat: position.coords.latitude,
                lng: position.coords.longitude,
              };
              resolve();
            },
            function () {
              state.currentPosition = DEFAULT_FALLBACK_POSITION;
              resolve();
            },
            { enableHighAccuracy: true, timeout: 8000 },
          );
        });
      } catch (_) {
        state.currentPosition = DEFAULT_FALLBACK_POSITION;
      }
    }

    showRouteLoading(destName);

    try {
      const route = await fetchWalkingRoute(
        state.currentPosition.lat,
        state.currentPosition.lng,
        destLat,
        destLng,
      );
      drawRouteOnMap(route.coordinates, state.currentPosition, {
        lat: destLat,
        lng: destLng,
      });
      state.routeSteps = route.steps || [];
      showRoutePanel(route.distance, route.duration, destName, route.steps || []);
    } catch (_) {
      showRouteError('경로를 찾지 못했습니다.');
    }
  }

  async function fetchWalkingRoute(startLat, startLng, endLat, endLng) {
    const url = 'https://router.project-osrm.org/route/v1/foot/'
      + `${startLng},${startLat};${endLng},${endLat}`
      + '?overview=full&geometries=geojson&steps=true';
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Route API ${response.status}`);
    const data = await response.json();
    if (!data.routes || !data.routes.length) throw new Error('No route');

    const route = data.routes[0];
    const steps = [];
    if (route.legs) {
      route.legs.forEach(function (leg) {
        if (!leg.steps) return;
        leg.steps.forEach(function (step) {
          if (!step.maneuver || step.distance <= 0) return;
          steps.push({
            instruction: translateManeuver(
              step.maneuver.type,
              step.maneuver.modifier,
              step.name,
            ),
            distance: step.distance,
            duration: step.duration,
          });
        });
      });
    }

    return {
      coordinates: route.geometry.coordinates,
      distance: route.distance,
      duration: route.duration,
      steps: steps,
    };
  }

  function translateManeuver(type, modifier, name) {
    const road = name ? ` (${name})` : '';
    const labels = {
      depart: '출발',
      arrive: '도착',
      'turn-left': '좌회전',
      'turn-right': '우회전',
      'turn-slight left': '왼쪽 방향 유지',
      'turn-slight right': '오른쪽 방향 유지',
      'turn-sharp left': '급좌회전',
      'turn-sharp right': '급우회전',
      'continue-straight': '직진',
      'continue-': '직진',
      'fork-left': '갈림길에서 왼쪽',
      'fork-right': '갈림길에서 오른쪽',
      'roundabout-': '로터리 진입',
      'new name-': '도로를 따라 이동',
    };

    const key = `${type}-${modifier || ''}`;
    const label = labels[key] || labels[`${type}-`] || `${type}${modifier ? ` ${modifier}` : ''}`;
    return label + road;
  }

  function drawRouteOnMap(geojsonCoords, startPos, endPos) {
    clearRoute();
    if (!state.map || !state.kakaoReady || !window.kakao?.maps) return;

    const path = geojsonCoords.map(function (coord) {
      return new kakao.maps.LatLng(coord[1], coord[0]);
    });

    state.routePolyline = new kakao.maps.Polyline({
      path: path,
      strokeWeight: 7,
      strokeColor: '#4285F4',
      strokeOpacity: 0.9,
      strokeStyle: 'solid',
    });
    state.routePolyline.setMap(state.map);

    try {
      const endSvg =
        '<svg xmlns="http://www.w3.org/2000/svg" width="36" height="44" viewBox="0 0 36 44">'
        + '<path d="M18 0C8.06 0 0 8.06 0 18c0 12.6 18 26 18 26s18-13.4 18-26C36 8.06 27.94 0 18 0z" fill="#EA4335"/>'
        + '<circle cx="18" cy="16" r="7" fill="white"/></svg>';
      state.routeEndMarker = new kakao.maps.Marker({
        position: new kakao.maps.LatLng(endPos.lat, endPos.lng),
        image: new kakao.maps.MarkerImage(
          `data:image/svg+xml;charset=utf-8,${encodeURIComponent(endSvg)}`,
          new kakao.maps.Size(36, 44),
          { offset: new kakao.maps.Point(18, 44) },
        ),
      });
      state.routeEndMarker.setMap(state.map);
    } catch (error) {
      console.warn('Failed to draw route markers.', error);
    }

    const bounds = new kakao.maps.LatLngBounds();
    path.forEach(function (point) {
      bounds.extend(point);
    });
    state.map.setBounds(bounds);
  }

  function clearRoute() {
    if (state.routePolyline) {
      state.routePolyline.setMap(null);
      state.routePolyline = null;
    }
    if (state.routeStartMarker) {
      state.routeStartMarker.setMap(null);
      state.routeStartMarker = null;
    }
    if (state.routeEndMarker) {
      state.routeEndMarker.setMap(null);
      state.routeEndMarker = null;
    }
    state.routeSteps = [];
    removePanel('route-panel');
  }

  function showRoutePanel(distM, durS, destName, steps) {
    removePanel('route-panel');

    const dist = distM >= 1000 ? `${(distM / 1000).toFixed(1)} km` : `${Math.round(distM)} m`;
    const mins = Math.ceil(durS / 60);
    const time = mins >= 60
      ? `${Math.floor(mins / 60)}h ${mins % 60}m`
      : `${mins}m`;

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel';

    let html = [
      '<div class="panel-row">',
      '<span class="panel-icon">도보</span>',
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      `<span>거리 ${escapeHtml(dist)} · 도보 ${escapeHtml(time)}</span>`,
      '</div>',
      steps && steps.length
        ? '<button class="panel-close" data-role="toggle">경로</button>'
        : '',
      '</div>',
    ].join('');

    if (steps && steps.length) {
      html += '<div id="route-steps" class="route-steps" style="display:none;">';
      steps.forEach(function (step) {
        const stepDistance = step.distance >= 1000
          ? `${(step.distance / 1000).toFixed(1)}km`
          : `${Math.round(step.distance)}m`;
        html += [
          '<div class="route-step">',
          `<span class="step-num">${escapeHtml(getStepIcon(step.instruction))}</span>`,
          `<span class="step-text">${escapeHtml(step.instruction)}</span>`,
          `<span class="step-dist">${escapeHtml(stepDistance)}</span>`,
          '</div>',
        ].join('');
      });
      html += '</div>';
    }

    panel.innerHTML = html;
    $('#map-shell').appendChild(panel);

    const toggleButton = panel.querySelector('[data-role="toggle"]');
    if (toggleButton) {
      toggleButton.addEventListener('click', function () {
        const stepElement = $('#route-steps');
        if (!stepElement) return;
        const hidden = stepElement.style.display === 'none';
        stepElement.style.display = hidden ? 'block' : 'none';
        toggleButton.textContent = hidden ? '접기' : '경로';
      });
    }
  }

  function getStepIcon(instruction) {
    if (instruction.includes('left') || instruction.includes('좌')) return '좌';
    if (instruction.includes('right') || instruction.includes('우')) return '우';
    if (instruction.includes('straight') || instruction.includes('직진')) return '직';
    if (instruction.includes('Depart') || instruction.includes('출발')) return '출발';
    if (instruction.includes('Arrive') || instruction.includes('도착')) return '도착';
    if (instruction.includes('fork') || instruction.includes('갈림길')) return '갈림';
    if (instruction.includes('roundabout') || instruction.includes('로터리')) return '회전';
    return '이동';
  }

  function showRouteLoading(destName) {
    removePanel('route-panel');

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel';
    panel.innerHTML = [
      '<div class="panel-row">',
      '<span class="panel-icon">...</span>',
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      '<span>경로를 찾는 중입니다.</span>',
      '</div>',
      '</div>',
    ].join('');
    $('#map-shell').appendChild(panel);
  }

  function showRouteError(message) {
    removePanel('route-panel');

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel route-error';
    panel.innerHTML = [
      '<div class="panel-row">',
      '<span class="panel-icon">...</span>',
      `<div class="panel-info"><span>${escapeHtml(message)}</span></div>`,
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
  }

  function showMapStatus(message) {
    $('#map').innerHTML = `<div class="map-message">${escapeHtml(message)}</div>`;
  }

  function showMapError(message) {
    $('#map').innerHTML = `<div class="map-message error">${escapeHtml(message)}</div>`;
  }

  function removePanel(id) {
    const target = document.getElementById(id);
    if (target) target.remove();
  }

  function dismissTransientUi() {
    removePanel('marker-info');
    removePanel('festival-detail');
    clearRoute();
  }

  function distanceMeters(lat1, lng1, lat2, lng2) {
    const earthRadius = 6371000;
    const dLat = toRadians(lat2 - lat1);
    const dLng = toRadians(lng2 - lng1);
    const a = Math.sin(dLat / 2) ** 2 +
      Math.cos(toRadians(lat1)) *
      Math.cos(toRadians(lat2)) *
      Math.sin(dLng / 2) ** 2;
    return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  function toRadians(value) {
    return value * Math.PI / 180;
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, function (char) {
      return {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        '\'': '&#039;',
      }[char];
    });
  }

  function escapeAttribute(value) {
    return escapeHtml(value).replace(/`/g, '&#096;');
  }

  function stripHtml(value) {
    return String(value || '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  document.addEventListener('DOMContentLoaded', init);
})();


