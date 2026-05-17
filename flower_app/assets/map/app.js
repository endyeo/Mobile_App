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
    routePolylines: [],
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
    console.log('FlowerMap init', {
      hasKakaoKey: !!config.KAKAO_APP_KEY,
      apiBaseUrl: config.API_BASE_URL || '',
      embedded: !!config.EMBEDDED,
      locationHref: window.location.href,
      baseUri: document.baseURI,
    });
    exposeFlutterBridge();
    bindKakaoEvents();
    bindMapDomDiagnostics();
    initGeolocation();
    loadFlowers();
    loadFestivals();
  }

  function isKoreanMapPosition(lat, lng) {
    return Number(lat) >= 33.0 &&
      Number(lat) <= 38.9 &&
      Number(lng) >= 124.0 &&
      Number(lng) <= 132.0;
  }

  function bindMapDomDiagnostics() {
    const mapElement = $('#map');
    if (!mapElement || mapElement.dataset.diagnosticsBound === '1') return;

    mapElement.dataset.diagnosticsBound = '1';
    mapElement.addEventListener('load', function (event) {
      const target = event.target;
      if (target && target.tagName === 'IMG') {
        console.log('Map tile loaded', target.currentSrc || target.src || '');
      }
    }, true);

    mapElement.addEventListener('error', function (event) {
      const target = event.target;
      if (target && target.tagName === 'IMG') {
        console.error('Map tile failed', target.currentSrc || target.src || '');
      }
    }, true);
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
        if (!isKoreanMapPosition(lat, lng)) {
          console.warn('Ignoring non-Korean current position', lat, lng);
          return;
        }
        state.currentPosition = { lat: Number(lat), lng: Number(lng) };
        if (state.map && state.kakaoReady) {
          state.map.setCenter(new kakao.maps.LatLng(lat, lng));
        }
        applyFilters();
      },
      resetToDefaultCenter() {
        state.currentPosition = null;
        if (state.map && state.kakaoReady) {
          state.map.setCenter(
            new kakao.maps.LatLng(
              DEFAULT_FALLBACK_POSITION.lat,
              DEFAULT_FALLBACK_POSITION.lng,
            ),
          );
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
      console.log('FlowerMap received kakao-map-ready');
      state.kakaoReady = true;
      state.mapError = null;
      renderMap();
    });

    document.addEventListener('kakao-map-error', function () {
      console.error('FlowerMap received kakao-map-error');
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
      });
      const response = await fetch(`${baseUrl}/flower-spots?${params.toString()}`);
      if (!response.ok) return [];
      const body = await response.json();
      const posts = body.data?.posts ?? (Array.isArray(body) ? body : []);
      return normalizeFlowers(posts);
    } catch (error) {
      console.warn('Flower API is unavailable.', error);
      return [];
    }
  }

  function normalizeFlowers(flowers) {
    return flowers
      .map(function (flower) {
        const lat = flower.latitude ?? flower.location?.lat ?? flower.lat ?? flower.mapY;
        const lng = flower.longitude ?? flower.location?.lng ?? flower.lng ?? flower.mapX;
        return {
          flower_id: flower.id ?? flower.flower_id ?? flower.flowerId,
          name: flower.plantName || flower.name || flower.nickname || '',
          species: flower.flowerSpecies || flower.species || '',
          address: flower.address || '',
          imageUrl: flower.imageUrl || '',
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
      imageUrl: normalizeImageUrl(
        item.imageUrl || item.firstimage || item.firstimage2 || '',
      ),
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
      const mapElement = $('#map');
      if (!mapElement) {
        console.error('Map container not found');
        return false;
      }

      if (!state.map) {
        const center = config.DEFAULT_CENTER || { lat: 37.5665, lng: 126.9780 };
        mapElement.innerHTML = '';
        console.log('Creating Kakao map', center);
        state.map = new kakao.maps.Map(mapElement, {
          center: new kakao.maps.LatLng(center.lat, center.lng),
          level: config.DEFAULT_ZOOM_LEVEL || 5,
        });
        window.setTimeout(function () {
          try {
            state.map.relayout();
          } catch (error) {
            console.warn('Map relayout failed.', error);
          }
        }, 120);
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
        if (!isKoreanMapPosition(
          position.coords.latitude,
          position.coords.longitude,
        )) {
          console.warn(
            'Ignoring browser geolocation outside Korea',
            position.coords.latitude,
            position.coords.longitude,
          );
          applyFilters();
          return;
        }
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
    state.map.panTo(position);

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

  function positionFloatingPanel(panel, lat, lng, options) {
    if (!panel || !state.map || !window.kakao?.maps) return;

    const offsetY = options?.offsetY ?? 18;
    const minMargin = options?.minMargin ?? 12;
    const maxWidth = options?.maxWidth ?? window.innerWidth - 24;
    panel.style.maxWidth = `${maxWidth}px`;

    window.requestAnimationFrame(function () {
      try {
        const projection = state.map.getProjection();
        const point = projection.containerPointFromCoords(
          new kakao.maps.LatLng(lat, lng),
        );
        const panelWidth = panel.offsetWidth || Math.min(260, maxWidth);
        const panelHeight = panel.offsetHeight || 84;
        const left = Math.min(
          Math.max(point.x - panelWidth / 2, minMargin),
          window.innerWidth - panelWidth - minMargin,
        );
        const top = Math.max(point.y - panelHeight - offsetY, minMargin);
        panel.style.left = `${left}px`;
        panel.style.right = 'auto';
        panel.style.top = `${top}px`;
        panel.style.bottom = 'auto';
      } catch (_) {
        panel.style.left = `${minMargin}px`;
        panel.style.right = `${minMargin}px`;
        panel.style.top = `${minMargin}px`;
        panel.style.bottom = 'auto';
      }
    });
  }

  function showMarkerInfo(item) {
    dismissTransientUi();
    panToMarkerWithOffset(new kakao.maps.LatLng(item.lat, item.lng), 120);

    const panel = document.createElement('div');
    panel.id = 'marker-info';
    panel.className = 'map-panel marker-panel marker-panel-floating';

    const meta = [item.period, item.address].filter(Boolean).join(' · ');
    panel.innerHTML = [
      '<div class="panel-info">',
      `<strong>${escapeHtml(item.name || '')}</strong>`,
      meta ? `<span>${escapeHtml(meta)}</span>` : '',
      '</div>',
      '<div class="panel-actions">',
      '<button class="panel-action" data-role="navigate">길찾기</button>',
      item.isFestival
        ? '<button class="panel-action ghost" data-role="detail">정보</button>'
        : '',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
    positionFloatingPanel(panel, item.lat, item.lng, { offsetY: 22, maxWidth: 260 });

    panel.querySelector('[data-role="navigate"]').addEventListener('click', function () {
      panel.remove();
      showRouteModeChooser(item);
    });

    const detailButton = panel.querySelector('[data-role="detail"]');
    if (detailButton) {
      detailButton.addEventListener('click', function () {
        showFestivalDetail(item.festival || item);
      });
    }
  }

  function showRouteModeChooser(item) {
    removePanel('route-mode-panel');
    panToMarkerWithOffset(new kakao.maps.LatLng(item.lat, item.lng), 156);

    const panel = document.createElement('div');
    panel.id = 'route-mode-panel';
    panel.className = 'map-panel marker-panel marker-panel-floating route-mode-panel';
    panel.innerHTML = [
      '<div class="panel-info">',
      `<strong>${escapeHtml(item.name || '')}</strong>`,
      '<span>이동 방법을 선택하세요.</span>',
      '</div>',
      '<div class="panel-actions">',
      '<button class="panel-action secondary" data-role="walk">도보</button>',
      '<button class="panel-action" data-role="transit">대중교통</button>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
    positionFloatingPanel(panel, item.lat, item.lng, { offsetY: 22, maxWidth: 260 });

    panel.querySelector('[data-role="walk"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제', 'walk');
    });

    panel.querySelector('[data-role="transit"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제', 'transit');
    });
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
      '<button class="detail-close" type="button" data-role="close" aria-label="닫기">×</button>',
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
      '<button class="detail-button secondary" data-role="walk">도보</button>',
      '<button class="detail-button primary" data-role="transit">대중교통</button>',
      '</div>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);

    panel.querySelector('[data-role="close"]').addEventListener('click', function () {
      panel.remove();
    });

    panel.querySelector('[data-role="walk"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(
        enrichedFestival.mapY,
        enrichedFestival.mapX,
        enrichedFestival.title || '축제',
        'walk',
      );
    });

    panel.querySelector('[data-role="transit"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(
        enrichedFestival.mapY,
        enrichedFestival.mapX,
        enrichedFestival.title || '축제',
        'transit',
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
        imageUrl: normalizeImageUrl(common.imageUrl || festival.imageUrl || ''),
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
      imageUrl: normalizeImageUrl(item.firstimage || item.firstimage2 || ''),
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

  async function navigateInApp(destLat, destLng, destName, mode) {
    const routeMode = mode || 'walk';
    await ensureCurrentPosition();

    showRouteLoading(destName, routeMode);

    try {
      if (routeMode === 'transit') {
        const transitRoute = await fetchTransitRoute(
          state.currentPosition.lat,
          state.currentPosition.lng,
          destLat,
          destLng,
          destName,
        );
        drawTransitRouteOnMap(transitRoute, {
          lat: state.currentPosition.lat,
          lng: state.currentPosition.lng,
        }, {
          lat: destLat,
          lng: destLng,
        });
        showTransitRoutePanel(transitRoute, destName);
        return;
      }

      const walkingRoute = await fetchWalkingRoute(
        state.currentPosition.lat,
        state.currentPosition.lng,
        destLat,
        destLng,
      );
      drawRouteOnMap(walkingRoute.coordinates, state.currentPosition, {
        lat: destLat,
        lng: destLng,
      });
      state.routeSteps = walkingRoute.steps || [];
      showRoutePanel(
        walkingRoute.distance,
        walkingRoute.duration,
        destName,
        walkingRoute.steps || [],
      );
    } catch (error) {
      console.warn('Route lookup failed.', error);
      showRouteError(
        routeMode === 'transit'
          ? '대중교통 경로를 찾지 못했습니다.'
          : '도보 경로를 찾지 못했습니다.',
      );
    }
  }

  async function ensureCurrentPosition() {
    if (state.currentPosition) return;

    try {
      await new Promise(function (resolve) {
        if (!navigator.geolocation) {
          state.currentPosition = DEFAULT_FALLBACK_POSITION;
          resolve();
          return;
        }

        navigator.geolocation.getCurrentPosition(
          function (position) {
            if (isKoreanMapPosition(
              position.coords.latitude,
              position.coords.longitude,
            )) {
              state.currentPosition = {
                lat: position.coords.latitude,
                lng: position.coords.longitude,
              };
            } else {
              state.currentPosition = DEFAULT_FALLBACK_POSITION;
            }
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

  async function fetchTransitRoute(startLat, startLng, endLat, endLng, destName) {
    const response = await fetch(`${config.API_BASE_URL}/map/transit-route`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        startLat: startLat,
        startLng: startLng,
        endLat: endLat,
        endLng: endLng,
      }),
    });

    const body = await response.json().catch(function () {
      return null;
    });

    if (!response.ok || !body?.success || !body?.data) {
      throw new Error(body?.error?.message || `Transit API ${response.status}`);
    }

    return body.data;
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

    const polyline = new kakao.maps.Polyline({
      path: path,
      strokeWeight: 7,
      strokeColor: '#4285F4',
      strokeOpacity: 0.9,
      strokeStyle: 'solid',
    });
    polyline.setMap(state.map);
    state.routePolylines.push(polyline);

    drawRouteMarkers(endPos);

    const bounds = new kakao.maps.LatLngBounds();
    path.forEach(function (point) {
      bounds.extend(point);
    });
    state.map.setBounds(bounds);
  }

  function drawTransitRouteOnMap(route, startPos, endPos) {
    clearRoute();
    if (!state.map || !state.kakaoReady || !window.kakao?.maps) return;

    const bounds = new kakao.maps.LatLngBounds();
    let hasPath = false;

    (route.legs || []).forEach(function (leg) {
      const path = (leg.polyline || [])
        .filter(function (point) {
          return Number.isFinite(point.lat) && Number.isFinite(point.lng);
        })
        .map(function (point) {
          const latLng = new kakao.maps.LatLng(point.lat, point.lng);
          bounds.extend(latLng);
          hasPath = true;
          return latLng;
        });

      if (path.length < 2) return;

      const polyline = new kakao.maps.Polyline({
        path: path,
        strokeWeight: leg.mode === 'WALK' ? 5 : 7,
        strokeColor: normalizeRouteColor(leg.routeColor, leg.mode),
        strokeOpacity: 0.92,
        strokeStyle: leg.mode === 'WALK' ? 'shortdash' : 'solid',
      });
      polyline.setMap(state.map);
      state.routePolylines.push(polyline);
    });

    drawRouteMarkers(endPos);
    if (startPos && Number.isFinite(startPos.lat) && Number.isFinite(startPos.lng)) {
      bounds.extend(new kakao.maps.LatLng(startPos.lat, startPos.lng));
    }
    if (endPos && Number.isFinite(endPos.lat) && Number.isFinite(endPos.lng)) {
      bounds.extend(new kakao.maps.LatLng(endPos.lat, endPos.lng));
    }

    if (hasPath) {
      state.map.setBounds(bounds);
    } else if (endPos) {
      state.map.panTo(new kakao.maps.LatLng(endPos.lat, endPos.lng));
    }
  }

  function drawRouteMarkers(endPos) {
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
  }

  function normalizeRouteColor(color, mode) {
    const text = String(color || '').trim();
    if (/^#[0-9a-f]{6}$/i.test(text)) return text;
    if (/^[0-9a-f]{6}$/i.test(text)) return `#${text}`;
    if (mode === 'SUBWAY') return '#2F80ED';
    if (mode === 'BUS') return '#27AE60';
    if (mode === 'WALK') return '#6B7280';
    return '#8B5CF6';
  }

  function clearRoute() {
    state.routePolylines.forEach(function (polyline) {
      polyline.setMap(null);
    });
    state.routePolylines = [];
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

  function showTransitRoutePanel(route, destName) {
    removePanel('route-panel');

    const summary = route.summary || {};
    const totalTime = formatDuration(summary.totalTimeSec || 0);
    const walkTime = formatDuration(summary.totalWalkTimeSec || 0);
    const fare = formatFare(summary.totalFare || 0);
    const transfers = Number(summary.transferCount || 0);

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel transit-route-panel';

    let html = [
      '<div class="panel-row panel-row-start">',
      '<span class="panel-icon">교통</span>',
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      `<span>대중교통 ${escapeHtml(totalTime)} · 도보 ${escapeHtml(walkTime)} · 환승 ${escapeHtml(String(transfers))}회 · ${escapeHtml(fare)}</span>`,
      '</div>',
      '<button class="panel-close" data-role="toggle">구간</button>',
      '</div>',
      '<div id="route-steps" class="route-steps transit-steps" style="display:block;">',
    ].join('');

    (route.legs || []).forEach(function (leg) {
      html += buildTransitLegHtml(leg);
    });
    html += '</div>';

    panel.innerHTML = html;
    $('#map-shell').appendChild(panel);

    const toggleButton = panel.querySelector('[data-role="toggle"]');
    if (toggleButton) {
      toggleButton.addEventListener('click', function () {
        const stepElement = $('#route-steps');
        if (!stepElement) return;
        const hidden = stepElement.style.display === 'none';
        stepElement.style.display = hidden ? 'block' : 'none';
        toggleButton.textContent = hidden ? '접기' : '구간';
      });
    }
  }

  function buildTransitLegHtml(leg) {
    const modeLabel = getTransitModeLabel(leg.mode);
    const duration = formatDuration(leg.durationSec || 0);
    const distance = formatDistance(leg.distanceM || 0);
    const badgeStyle = leg.mode === 'WALK'
      ? ''
      : ` style="background:${escapeAttribute(normalizeRouteColor(leg.routeColor, leg.mode))};color:#fff;"`;
    const routeLabel = leg.route
      ? `<span class="route-badge"${badgeStyle}>${escapeHtml(leg.route)}</span>`
      : `<span class="route-badge subtle">${escapeHtml(modeLabel)}</span>`;
    const title = leg.mode === 'WALK'
      ? `${escapeHtml(leg.startName || '현재 위치')}에서 ${escapeHtml(leg.endName || '도착지')}까지 도보 이동`
      : `${routeLabel}<span class="route-places">${escapeHtml(leg.startName || '')} → ${escapeHtml(leg.endName || '')}</span>`;
    const subtext = [];
    subtext.push(`${escapeHtml(modeLabel)} ${escapeHtml(duration)}`);
    if ((leg.stationCount || 0) > 0) {
      subtext.push(`${escapeHtml(String(leg.stationCount))}정류장`);
    }
    subtext.push(escapeHtml(distance));
    const instructionHtml = (leg.instructions || [])
      .slice(0, 6)
      .map(function (instruction) {
        return `<li>${escapeHtml(instruction)}</li>`;
      })
      .join('');

    return [
      '<div class="transit-leg">',
      `<div class="transit-leg-header transit-${escapeAttribute(String(leg.mode || '').toLowerCase())}">`,
      `<span class="transit-mode">${escapeHtml(modeLabel)}</span>`,
      `<span class="transit-summary">${subtext.join(' · ')}</span>`,
      '</div>',
      `<div class="transit-leg-title">${title}</div>`,
      instructionHtml ? `<ul class="transit-instructions">${instructionHtml}</ul>` : '',
      '</div>',
    ].join('');
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

  function getTransitModeLabel(mode) {
    switch (mode) {
      case 'BUS':
        return '버스';
      case 'SUBWAY':
        return '지하철';
      case 'EXPRESSBUS':
        return '고속버스';
      case 'TRAIN':
        return '기차';
      case 'AIRPLANE':
        return '항공';
      case 'FERRY':
        return '해운';
      default:
        return '도보';
    }
  }

  function formatDuration(seconds) {
    const mins = Math.max(1, Math.ceil(Number(seconds || 0) / 60));
    if (mins >= 60) {
      return `${Math.floor(mins / 60)}시간 ${mins % 60}분`;
    }
    return `${mins}분`;
  }

  function formatDistance(distanceM) {
    return Number(distanceM || 0) >= 1000
      ? `${(Number(distanceM) / 1000).toFixed(1)}km`
      : `${Math.round(Number(distanceM || 0))}m`;
  }

  function formatFare(fare) {
    return `${Number(fare || 0).toLocaleString('ko-KR')}원`;
  }

  function showRouteLoading(destName, mode) {
    removePanel('route-panel');

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel';
    panel.innerHTML = [
      '<div class="panel-row">',
      `<span class="panel-icon">${mode === 'transit' ? '교통' : '도보'}</span>`,
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      `<span>${mode === 'transit' ? '대중교통 경로를 찾는 중입니다.' : '도보 경로를 찾는 중입니다.'}</span>`,
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
    removePanel('route-mode-panel');
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

  function normalizeImageUrl(value) {
    const text = String(value || '').trim();
    if (text.startsWith('http://')) {
      return `https://${text.slice(7)}`;
    }
    return text;
  }

  document.addEventListener('DOMContentLoaded', init);
})();


