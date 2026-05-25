(function () {
  'use strict';

  const config = window.MAP_CONFIG || {};
  const DEFAULT_FALLBACK_POSITION = { lat: 37.5665, lng: 126.9780 };
  const state = {
    map: null,
    flowers: [],
    filteredFlowers: [],
    festivals: [],
    touristSpots: [],
    filteredMapItems: [],
    currentPosition: null,
    currentAccuracy: null,
    radius: config.DEFAULT_RADIUS || 5000,
    search: '',
    searchFilter: 'all',
    // 검색 리스트에서 선택한 항목을 기억해야 네이버 지도처럼 선택 상태를 계속 보여줄 수 있다.
    selectedSearchItemId: '',
    // 검색 결과 패널은 접힘/중간/확장 상태를 오가므로 현재 슬라이드 단계를 상태로 보관한다.
    searchPanelState: 'half',
    showRecentSearches: false,
    kakaoReady: false,
    mapError: null,
    markers: [],
    festivalMarkers: [],
    clusterMarkers: [],
    currentPositionMarker: null,
    currentAccuracyCircle: null,
    routePolylines: [],
    routeStartMarker: null,
    routeEndMarker: null,
    routeBounds: null,
    routeSteps: [],
    pendingFlowerFocus: null,
    pendingRouteAction: null,
    showFlowers: true,
    showFestivals: true,
    showTouristSpots: true,
    mapInteractionBound: false,
    suppressMapDismissUntil: 0,
    savedPlaceKeys: new Set(),
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
  const TOURIST_SPOT_KEYWORDS = [
    ...FLOWER_KEYWORDS,
    '\uC218\uBAA9\uC6D0',
    '\uC2DD\uBB3C\uC6D0',
    '\uC815\uC6D0',
    '\uACF5\uC6D0',
    '\uC232',
    '\uC0DD\uD0DC\uC6D0',
    '\uC0DD\uD0DC\uACF5\uC6D0',
    '\uC790\uC5F0\uD734\uC591\uB9BC',
    '\uAF43\uAE38',
    '\uC0B0\uCC45\uB85C',
    '\uB458\uB808\uAE38',
    '\uB18D\uC6D0',
    '\uD654\uC6D0',
    'arboretum',
    'botanical',
    'garden',
    'park',
  ];
  const CATEGORY_COLORS = {
    flower: '#FBBF24',
    festival: '#ff4fa3',
    tourist: '#10b981',
  };

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
    applyInitialConfig();
    bindKakaoEvents();
    bindMapDomDiagnostics();
    initGeolocation();
    loadFlowers();
    loadFestivals();
    loadTouristSpots();
  }

  function applyInitialConfig() {
    const initialSearch = String(config.INITIAL_SEARCH_QUERY || '').trim();
    if (initialSearch) {
      state.search = initialSearch;
    }
    const initialFlowerId = String(config.INITIAL_FOCUS_FLOWER_ID || '').trim();
    if (initialFlowerId) {
      state.pendingFlowerFocus = {
        flowerId: initialFlowerId,
        openPreview: config.INITIAL_OPEN_FLOWER_PREVIEW !== false,
      };
    }
    const initialRouteFlowerId = String(config.INITIAL_ROUTE_FLOWER_ID || '').trim();
    if (initialRouteFlowerId) {
      state.pendingRouteAction = {
        flowerId: initialRouteFlowerId,
        mode: normalizeBridgeRouteMode(config.INITIAL_ROUTE_MODE || ''),
      };
    }
  }

  function normalizeBridgeRouteMode(mode) {
    const lower = String(mode || '').trim().toLowerCase();
    if (lower === 'walk' || lower === 'car' || lower === 'transit') return lower;
    return 'none';
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
        state.searchFilter = 'all';
        state.selectedSearchItemId = '';
        state.searchPanelState = state.search ? 'half' : 'collapsed';
        state.showRecentSearches = !state.search;
        rememberSearchQuery(state.search);
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
      setRoutePanelTop(top) {
        const nextTop = Math.max(96, Number(top || 0));
        document.documentElement.style.setProperty('--route-panel-top', `${nextTop}px`);
        refitVisibleRoute();
      },
      setCurrentPosition(lat, lng) {
        if (!isKoreanMapPosition(lat, lng)) {
          console.warn('Ignoring non-Korean current position', lat, lng);
          return;
        }
        state.currentPosition = { lat: Number(lat), lng: Number(lng) };
        if (state.map && state.kakaoReady) {
          markProgrammaticMove();
          state.map.setCenter(new kakao.maps.LatLng(lat, lng));
          renderCurrentPositionMarker();
        }
        applyFilters();
      },
      resetToDefaultCenter() {
        state.currentPosition = null;
        state.currentAccuracy = null;
        clearCurrentPositionMarker();
        if (state.map && state.kakaoReady) {
          markProgrammaticMove();
          state.map.setCenter(
            new kakao.maps.LatLng(
              DEFAULT_FALLBACK_POSITION.lat,
              DEFAULT_FALLBACK_POSITION.lng,
            ),
          );
          renderCurrentPositionMarker();
        }
        applyFilters();
      },
      setVisibleCategories(flowers, festivals, touristSpots) {
        state.showFlowers = !!flowers;
        state.showFestivals = !!festivals;
        state.showTouristSpots = touristSpots !== false;
        applyFilters();
      },
      setFestivals(festivals) {
        try {
          const parsed = typeof festivals === 'string'
            ? JSON.parse(festivals)
            : festivals;
          state.festivals = normalizeFestivalList(parsed);
          applyFilters();
        } catch (error) {
          console.warn('Failed to set festivals from Flutter.', error);
        }
      },
      setTouristSpots(spots) {
        try {
          const parsed = typeof spots === 'string'
            ? JSON.parse(spots)
            : spots;
          state.touristSpots = normalizeTouristSpotList(parsed);
          applyFilters();
        } catch (error) {
          console.warn('Failed to set tourist spots from Flutter.', error);
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
      focusFlowerById(flowerId, openPreview) {
        focusFlowerById(flowerId, openPreview !== false);
      },
      openRouteChooserByFlowerId(flowerId) {
        openRouteChooserByFlowerId(flowerId);
      },
      startRouteToFlowerById(flowerId, mode) {
        startRouteToFlowerById(flowerId, mode);
      },
      setSavedPlaceKeys(keys) {
        const list = Array.isArray(keys) ? keys : [];
        state.savedPlaceKeys = new Set(list.map(function (key) {
          return String(key || '');
        }));
        refreshSaveButtons();
      },
      focusSavedPlace(place) {
        focusSavedPlace(place);
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
    applyPendingFlowerFocus();
    applyPendingRouteAction();
  }

  async function fetchFlowersFromApi() {
    const baseUrl = config.API_BASE_URL;
    if (!baseUrl) return [];

    try {
      // 지도 전체 뷰: 위치 필터 없이 최근 게시글 전부 표시
      const response = await fetch(`${baseUrl}/flower-spots`);
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
        // 백엔드 응답 필드는 latitude/longitude — 그 외 클라이언트 호환 키도 보존
        const lat = flower.latitude ?? flower.location?.lat ?? flower.lat ?? flower.mapY;
        const lng = flower.longitude ?? flower.location?.lng ?? flower.lng ?? flower.mapX;
        return {
          flower_id: flower.id ?? flower.flower_id ?? flower.flowerId,
          name: flower.plantName || flower.name || '',
          species: flower.flowerSpecies || flower.species || '',
          address: flower.address || '',
          imageUrl: flower.imageUrl || '',
          content: flower.content || '',
          nickname: flower.nickname || '',
          profileImageUrl: flower.profileImageUrl || '',
          createdAt: flower.createdAt || '',
          likeCount: flower.likeCount ?? 0,
          commentCount: flower.commentCount ?? 0,
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
      applyFilters();
    } catch (error) {
      console.warn('Festival API is unavailable.', error);
    }
  }

  async function loadTouristSpots() {
    const tourKey = config.TOUR_API_KEY;
    if (!tourKey) return;

    try {
      const merged = [];
      const keywords = TOURIST_SPOT_KEYWORDS
        .filter(function (keyword) {
          return !/^[a-z]+$/i.test(String(keyword));
        })
        .slice(0, 12);

      for (const keyword of keywords) {
        if (merged.length >= 300) break;
        const params = new URLSearchParams({
          serviceKey: tourKey,
          numOfRows: '35',
          pageNo: '1',
          MobileOS: 'ETC',
          MobileApp: 'FlowerApp',
          _type: 'json',
          keyword: keyword,
          arrange: 'A',
          contentTypeId: '12',
        });

        const response = await fetch(
          `https://apis.data.go.kr/B551011/KorService2/searchKeyword2?${params.toString()}`,
        );
        if (!response.ok) continue;
        const data = await response.json();
        const items = data?.response?.body?.items?.item;
        const itemList = Array.isArray(items) ? items : items ? [items] : [];
        merged.push(...itemList);
      }

      state.touristSpots = normalizeTouristSpotList(dedupeByContentId(merged)).slice(0, 300);
      applyFilters();
    } catch (error) {
      console.warn('Tourist spot API is unavailable.', error);
    }
  }

  function dedupeByContentId(items) {
    const map = new Map();
    (items || []).forEach(function (item) {
      const key = item.contentid || item.contentId || `${item.title}_${item.mapx}_${item.mapy}`;
      if (!map.has(key)) map.set(key, item);
    });
    return Array.from(map.values());
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
      eventStartDate: item.eventStartDate || item.eventstartdate || '',
      eventEndDate: item.eventEndDate || item.eventenddate || '',
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
    if (isPastFestival(festival)) return null;
    return festival;
  }

  function normalizeTouristSpotList(list) {
    return (Array.isArray(list) ? list : [])
      .map(normalizeTouristSpot)
      .filter(Boolean);
  }

  function normalizeTouristSpot(item) {
    if (!item) return null;

    const spot = {
      contentId: item.contentId || item.contentid || '',
      title: item.title || '',
      address: item.address || [item.addr1 || '', item.addr2 || ''].join(' ').trim(),
      imageUrl: normalizeImageUrl(
        item.imageUrl || item.firstimage2 || item.firstimage || '',
      ),
      period: item.period || formatPeriod(
        item.eventStartDate || item.eventstartdate || '',
        item.eventEndDate || item.eventenddate || '',
      ),
      eventStartDate: item.eventStartDate || item.eventstartdate || '',
      eventEndDate: item.eventEndDate || item.eventenddate || '',
      mapX: Number(item.mapX ?? item.mapx ?? item.lng ?? item.longitude ?? 0),
      mapY: Number(item.mapY ?? item.mapy ?? item.lat ?? item.latitude ?? 0),
      contentTypeId: String(item.contentTypeId || item.contenttypeid || '12'),
      type: 'tourist',
    };

    if (!spot.title) return null;
    if (!Number.isFinite(spot.mapX) ||
        !Number.isFinite(spot.mapY) ||
        spot.mapX === 0 ||
        spot.mapY === 0) {
      return null;
    }

    if (hasFestivalPeriod(spot)) return null;
    if (!containsTouristSpotKeyword(`${spot.title} ${spot.address}`)) return null;
    return spot;
  }

  function isFlowerFestival(title) {
    return containsFlowerKeyword(title);
  }

  function hasFestivalPeriod(item) {
    return Boolean(
      item?.period ||
      item?.eventStartDate ||
      item?.eventstartdate ||
      item?.eventEndDate ||
      item?.eventenddate,
    );
  }

  function containsFlowerKeyword(value) {
    return containsKeyword(value, FLOWER_KEYWORDS);
  }

  function containsTouristSpotKeyword(value) {
    return containsKeyword(value, TOURIST_SPOT_KEYWORDS);
  }

  function containsKeyword(value, keywords) {
    const text = String(value || '').toLowerCase();
    return keywords.some(function (keyword) {
      return text.includes(String(keyword).toLowerCase());
    });
  }

  function isPastFestival(festival) {
    const end = String(
      festival.eventEndDate ||
      festival.eventenddate ||
      festival.eventEnd ||
      '',
    );
    if (!/^\d{8}$/.test(end)) return false;
    const endDate = new Date(
      Number(end.substring(0, 4)),
      Number(end.substring(4, 6)) - 1,
      Number(end.substring(6, 8)),
    );
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return endDate < today;
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

      // 거리는 표시용으로만 계산하고 반경 필터링은 하지 않음
      // (지도 전체 뷰: 위치 무관하게 모든 꽃 게시글 표시)
      if (center && !flower._test) {
        flower.distance_m = Math.round(
          distanceMeters(
            center.lat,
            center.lng,
            flower.location.lat,
            flower.location.lng,
          ),
        );
      }

      return true;
    });

    state.filteredMapItems = buildVisibleMapItems().filter(function (item) {
      if (!query) return true;
      if (state.searchFilter !== 'all' && item.type !== state.searchFilter) return false;
      return `${item.name} ${item.kindLabel} ${item.address} ${item.period || ''}`
        .toLowerCase()
        .includes(query);
    });

    renderMap();
    renderSearchResultsPanel();
    applyPendingFlowerFocus();
    applyPendingRouteAction();
  }

  function buildVisibleMapItems() {
    const items = [];

    if (state.showFlowers) {
      state.filteredFlowers.forEach(function (flower) {
        const item = flowerToMapItem(flower);
        if (item) items.push(item);
      });
    }

    if (state.showFestivals) {
      state.festivals.forEach(function (festival) {
        if (isPastFestival(festival)) return;
        items.push({
          id: `festival-${festival.contentId || `${festival.mapY},${festival.mapX}`}`,
          type: 'festival',
          kindLabel: '축제',
          name: festival.title || '축제',
          address: festival.address || '',
          lat: festival.mapY,
          lng: festival.mapX,
          isFestival: true,
          imageUrl: festival.imageUrl || '',
          period: festival.period || '',
          festival: festival,
          source: festival,
        });
      });
    }

    if (state.showTouristSpots) {
      state.touristSpots.forEach(function (spot) {
        items.push({
          id: `tourist-${spot.contentId || `${spot.mapY},${spot.mapX}`}`,
          type: 'tourist',
          kindLabel: '관광지',
          name: spot.title || '관광지',
          address: spot.address || '',
          lat: spot.mapY,
          lng: spot.mapX,
          isFestival: false,
          isTourist: true,
          imageUrl: spot.imageUrl || '',
          period: spot.period || '',
          source: spot,
        });
      });
    }

    return items;
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

    const items = state.filteredMapItems.length || state.search
      ? state.filteredMapItems
      : buildVisibleMapItems();
    const groups = clusterMapItems(items);

    groups.forEach(function (group) {
      if (group.items.length > 1) {
        renderClusterMarker(group);
        return;
      }
      renderSingleMapMarker(group.items[0]);
    });

    renderCurrentPositionMarker();
  }

  function renderSingleMapMarker(item) {
    const position = new kakao.maps.LatLng(item.lat, item.lng);
    const markerOptions = { position: position };
    if (item.type === 'festival') {
      markerOptions.image = buildFestivalMarkerImage();
    } else if (item.type === 'tourist') {
      markerOptions.image = buildTouristMarkerImage();
    } else {
      markerOptions.image = buildFlowerMarkerImage();
    }
    const marker = new kakao.maps.Marker(markerOptions);
    marker.setMap(state.map);
    kakao.maps.event.addListener(marker, 'click', function () {
      showMarkerInfo(item);
    });

    if (item.type === 'festival') {
      state.festivalMarkers.push(marker);
    } else {
      state.markers.push(marker);
    }
  }

  function renderSearchResultsPanel() {
    removePanel('search-results');
    const shell = $('#map-shell');
    if (!shell) return;

    const query = state.search.trim();
    if (!query) {
      if (state.showRecentSearches) renderRecentSearchPanel();
      return;
    }

    const results = state.filteredMapItems.slice(0, 30);
    const panel = document.createElement('section');
    panel.id = 'search-results';
    panel.className = `map-panel search-results-panel ${normalizeSearchPanelState()}`;
    panel.innerHTML = [
      buildSearchPanelHandle(),
      '<div class="search-results-topline">',
      '<div class="search-results-title">',
      `<strong>${escapeHtml(query)}</strong>`,
      `<span>${results.length}개 결과</span>`,
      '</div>',
      '<button type="button" class="search-panel-close" data-role="clear" aria-label="검색 지우기">×</button>',
      '</div>',
      '<div class="search-filter-row" role="tablist" aria-label="검색 결과 필터">',
      buildSearchFilterButton('all', '전체', state.filteredMapItems.length),
      buildSearchFilterButton('flower', '꽃 스팟', countSearchResultsByType('flower')),
      buildSearchFilterButton('festival', '축제', countSearchResultsByType('festival')),
      buildSearchFilterButton('tourist', '관광지', countSearchResultsByType('tourist')),
      '</div>',
      results.length
        ? `<div class="search-result-list">${results.map(buildSearchResultHtml).join('')}</div>`
        : '<div class="search-empty"><strong>검색 결과가 없습니다.</strong><span>다른 이름이나 지역명으로 다시 검색해 보세요.</span></div>',
    ].join('');

    shell.appendChild(panel);
    bindSearchPanelHandle(panel);
    panel.querySelector('[data-role="clear"]').addEventListener('click', function () {
      state.search = '';
      state.selectedSearchItemId = '';
      state.searchPanelState = 'collapsed';
      state.showRecentSearches = true;
      applyFilters();
    });
    panel.querySelectorAll('.search-result-item').forEach(function (button) {
      button.addEventListener('click', function () {
        const item = results[Number(button.dataset.index)];
        if (item) {
          rememberSearchQuery(query);
          state.selectedSearchItemId = item.id;
          // 선택한 항목을 즉시 강조해서 리스트와 지도 마커가 같은 장소를 가리킨다는 피드백을 준다.
          panel.querySelectorAll('.search-result-item').forEach(function (entry) {
            entry.classList.toggle('selected', entry.dataset.itemId === item.id);
          });
          showMarkerInfo(item);
        }
      });
    });
    panel.querySelectorAll('[data-filter]').forEach(function (button) {
      button.addEventListener('click', function () {
        state.searchFilter = button.dataset.filter || 'all';
        applyFilters();
      });
    });
  }

  function renderRecentSearchPanel() {
    const recent = getRecentSearches();
    if (!recent.length) return;
    const shell = $('#map-shell');
    if (!shell) return;
    const panel = document.createElement('section');
    panel.id = 'search-results';
    panel.className = `map-panel search-results-panel recent ${normalizeSearchPanelState()}`;
    panel.innerHTML = [
      buildSearchPanelHandle(),
      '<div class="search-results-topline">',
      '<div class="search-results-title">',
      '<strong>최근 검색어</strong>',
      '<span>다시 검색하기</span>',
      '</div>',
      '<button type="button" class="search-panel-close" data-role="close" aria-label="닫기">×</button>',
      '</div>',
      `<div class="recent-search-list">${recent.map(function (query, index) {
        return `<button type="button" class="recent-search-item" data-index="${index}">${escapeHtml(query)}</button>`;
      }).join('')}</div>`,
    ].join('');
    shell.appendChild(panel);
    bindSearchPanelHandle(panel);
    panel.querySelector('[data-role="close"]').addEventListener('click', function () {
      state.searchPanelState = 'collapsed';
      panel.remove();
    });
    panel.querySelectorAll('.recent-search-item').forEach(function (button) {
      button.addEventListener('click', function () {
        state.search = recent[Number(button.dataset.index)] || '';
        state.selectedSearchItemId = '';
        state.searchPanelState = 'half';
        state.showRecentSearches = false;
        applyFilters();
      });
    });
  }

  function buildSearchFilterButton(type, label, count) {
    const selected = state.searchFilter === type ? ' class="selected"' : '';
    const ariaSelected = state.searchFilter === type ? 'true' : 'false';
    return [
      `<button type="button" role="tab" aria-selected="${ariaSelected}" data-filter="${escapeAttribute(type)}"${selected}>`,
      `<span>${escapeHtml(label)}</span>`,
      `<small>${escapeHtml(String(count || 0))}</small>`,
      '</button>',
    ].join('');
  }

  function buildSearchResultHtml(item, index) {
    const distance = state.currentPosition
      ? formatDistance(distanceMeters(state.currentPosition.lat, state.currentPosition.lng, item.lat, item.lng))
      : '';
    const typeLabel = getSearchResultTypeLabel(item);
    const selected = state.selectedSearchItemId === item.id ? ' selected' : '';
    const image = item.imageUrl
      ? `<img src="${escapeAttribute(item.imageUrl)}" alt="">`
      : `<span>${escapeHtml(typeLabel)}</span>`;
    const address = item.address || '주소 정보 없음';
    // 항목 내부 정보를 분리해두면 모바일에서도 장소명, 주소, 거리의 우선순위가 분명해진다.
    return [
      `<button type="button" class="search-result-item${selected}" data-index="${index}" data-item-id="${escapeAttribute(item.id)}">`,
      `<span class="search-result-thumb ${escapeAttribute(item.type)}">${image}</span>`,
      '<span class="search-result-copy">',
      '<span class="search-result-main">',
      `<strong>${escapeHtml(item.name || '')}</strong>`,
      `<em class="${escapeAttribute(item.type)}">${escapeHtml(typeLabel)}</em>`,
      '</span>',
      `<small>${escapeHtml(address)}</small>`,
      '<span class="search-result-meta">',
      item.kindLabel ? `<span>${escapeHtml(item.kindLabel)}</span>` : '',
      distance ? `<span>${escapeHtml(distance)}</span>` : '',
      '</span>',
      '</span>',
      '</button>',
    ].join('');
  }

  function countSearchResultsByType(type) {
    // 필터 탭의 숫자는 사용자가 탭을 누르기 전에 결과 규모를 예측하게 해준다.
    return state.filteredMapItems.filter(function (item) {
      return item.type === type;
    }).length;
  }

  function getSearchResultTypeLabel(item) {
    if (item.type === 'festival') return '축제';
    if (item.type === 'tourist') return '관광';
    return '꽃';
  }

  function buildSearchPanelHandle() {
    return [
      '<button type="button" class="search-sheet-handle" data-role="slide" aria-label="검색 결과 패널 크기 변경">',
      '<span></span>',
      '</button>',
    ].join('');
  }

  function bindSearchPanelHandle(panel) {
    const handle = panel.querySelector('[data-role="slide"]');
    if (!handle) return;

    let startY = 0;
    let startHeight = 0;
    let currentHeight = 0;
    let dragging = false;
    let didDrag = false;

    handle.addEventListener('pointerdown', function (event) {
      if (event.button !== undefined && event.button !== 0) return;
      startY = event.clientY;
      startHeight = panel.getBoundingClientRect().height;
      currentHeight = startHeight;
      dragging = true;
      didDrag = false;
      panel.classList.add('dragging');
      if (handle.setPointerCapture) {
        handle.setPointerCapture(event.pointerId);
      }
      event.preventDefault();
    });

    handle.addEventListener('pointermove', function (event) {
      if (!dragging) return;
      const deltaY = startY - event.clientY;
      if (Math.abs(deltaY) > 3) didDrag = true;
      const bounds = getSearchPanelHeightBounds(panel);
      currentHeight = clamp(startHeight + deltaY, bounds.min, bounds.max);
      // 드래그 중에는 CSS 상태값보다 손가락 위치를 우선해 실제 높이를 바로 반영한다.
      panel.style.height = `${currentHeight}px`;
      event.preventDefault();
    });

    handle.addEventListener('pointerup', finishDrag);
    handle.addEventListener('pointercancel', finishDrag);

    handle.addEventListener('click', function () {
      if (handle.dataset.justDragged === '1') {
        delete handle.dataset.justDragged;
        return;
      }
      state.searchPanelState = nextSearchPanelState();
      applySearchPanelState(panel);
    });

    function finishDrag(event) {
      if (!dragging) return;
      dragging = false;
      panel.classList.remove('dragging');
      if (handle.releasePointerCapture) {
        try {
          handle.releasePointerCapture(event.pointerId);
        } catch (_) {}
      }
      if (!didDrag) {
        panel.style.height = '';
        return;
      }
      handle.dataset.justDragged = '1';
      state.searchPanelState = nearestSearchPanelState(panel, currentHeight);
      applySearchPanelState(panel);
      window.setTimeout(function () {
        delete handle.dataset.justDragged;
      }, 250);
      event.preventDefault();
    }
  }

  function applySearchPanelState(panel) {
    panel.classList.remove('collapsed', 'half', 'expanded');
    panel.classList.add(normalizeSearchPanelState());
    panel.style.height = '';
  }

  function nextSearchPanelState() {
    // 한 번 누를 때마다 접힘 -> 중간 -> 확장 순서로 이동해 드래그 없이도 슬라이드 느낌을 만든다.
    if (state.searchPanelState === 'collapsed') return 'half';
    if (state.searchPanelState === 'half') return 'expanded';
    return 'collapsed';
  }

  function normalizeSearchPanelState() {
    if (state.searchPanelState === 'collapsed' ||
        state.searchPanelState === 'expanded') {
      return state.searchPanelState;
    }
    return 'half';
  }

  function nearestSearchPanelState(panel, height) {
    const bounds = getSearchPanelHeightBounds(panel);
    const candidates = [
      { state: 'collapsed', height: bounds.min },
      { state: 'half', height: bounds.half },
      { state: 'expanded', height: bounds.max },
    ];
    return candidates.reduce(function (nearest, candidate) {
      return Math.abs(candidate.height - height) < Math.abs(nearest.height - height)
        ? candidate
        : nearest;
    }).state;
  }

  function getSearchPanelHeightBounds(panel) {
    const shell = $('#map-shell') || document.body;
    const shellHeight = shell.clientHeight || window.innerHeight || 640;
    const width = shell.clientWidth || window.innerWidth || 360;
    const min = width <= 360 ? 90 : 94;
    const halfRatio = width >= 768 ? 0.48 : width <= 420 ? 0.58 : 0.54;
    const halfMax = width >= 768 ? 420 : width <= 420 ? 440 : 430;
    const topGap = width >= 768 ? 132 : 124;
    const half = Math.min(shellHeight * halfRatio, halfMax);
    const max = Math.max(half, shellHeight - topGap);
    return { min: min, half: half, max: max };
  }

  function rememberSearchQuery(query) {
    const text = String(query || '').trim();
    if (!text) return;
    try {
      const recent = getRecentSearches().filter(function (entry) {
        return entry !== text;
      });
      recent.unshift(text);
      localStorage.setItem('ourtRecentMapSearches', JSON.stringify(recent.slice(0, 10)));
    } catch (_) {}
  }

  function getRecentSearches() {
    try {
      const raw = localStorage.getItem('ourtRecentMapSearches') || '[]';
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter(Boolean).slice(0, 10) : [];
    } catch (_) {
      return [];
    }
  }

  function clusterMapItems(items) {
    if (!state.map || !window.kakao?.maps) return [];
    const projection = state.map.getProjection();
    const groups = [];

    items.forEach(function (item) {
      const point = projection.containerPointFromCoords(
        new kakao.maps.LatLng(item.lat, item.lng),
      );
      let matched = null;
      for (const group of groups) {
        if (group.type !== item.type) {
          continue;
        }
        const dx = group.point.x - point.x;
        const dy = group.point.y - point.y;
        if (Math.sqrt(dx * dx + dy * dy) <= 44) {
          matched = group;
          break;
        }
      }

      if (matched) {
        matched.items.push(item);
        matched.point = new kakao.maps.Point(
          (matched.point.x * (matched.items.length - 1) + point.x) / matched.items.length,
          (matched.point.y * (matched.items.length - 1) + point.y) / matched.items.length,
        );
        matched.lat = average(matched.items.map(function (entry) { return entry.lat; }));
        matched.lng = average(matched.items.map(function (entry) { return entry.lng; }));
      } else {
        groups.push({
          point: point,
          lat: item.lat,
          lng: item.lng,
          type: item.type,
          items: [item],
        });
      }
    });

    return groups;
  }

  function renderClusterMarker(group) {
    const position = new kakao.maps.LatLng(group.lat, group.lng);
    const marker = new kakao.maps.Marker({
      position: position,
      image: buildClusterMarkerImage(group.items.length, group),
      zIndex: 12,
    });
    marker.setMap(state.map);
    kakao.maps.event.addListener(marker, 'click', function () {
      showClusterInfo(group);
    });
    state.clusterMarkers.push(marker);
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

  function buildFlowerMarkerImage() {
    const svg = [
      '<svg xmlns="http://www.w3.org/2000/svg" width="34" height="42" viewBox="0 0 34 42">',
      `<path d="M17 0C7.61 0 0 7.61 0 17c0 11.8 17 25 17 25s17-13.2 17-25C34 7.61 26.39 0 17 0z" fill="${CATEGORY_COLORS.flower}"/>`,
      '<circle cx="17" cy="16" r="8" fill="white"/>',
      `<circle cx="17" cy="16" r="4.5" fill="${CATEGORY_COLORS.flower}"/>`,
      '</svg>',
    ].join('');

    return new kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
      new kakao.maps.Size(34, 42),
      { offset: new kakao.maps.Point(17, 42) },
    );
  }

  function buildTouristMarkerImage() {
    const svg = [
      '<svg xmlns="http://www.w3.org/2000/svg" width="34" height="42" viewBox="0 0 34 42">',
      '<path d="M17 0C7.61 0 0 7.61 0 17c0 11.8 17 25 17 25s17-13.2 17-25C34 7.61 26.39 0 17 0z" fill="#10b981"/>',
      '<circle cx="17" cy="16" r="8" fill="white"/>',
      '<path d="M17 8l6 12h-4l-2 4-2-4h-4l6-12z" fill="#10b981"/>',
      '</svg>',
    ].join('');

    return new kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
      new kakao.maps.Size(34, 42),
      { offset: new kakao.maps.Point(17, 42) },
    );
  }

  function buildClusterMarkerImage(count, group) {
    const label = count > 99 ? '99+' : String(count);
    const fill = CATEGORY_COLORS[group?.type] || CATEGORY_COLORS.festival;
    const svg = [
      '<svg xmlns="http://www.w3.org/2000/svg" width="46" height="46" viewBox="0 0 46 46">',
      `<circle cx="23" cy="23" r="21" fill="${fill}" fill-opacity="0.94"/>`,
      '<circle cx="23" cy="23" r="17" fill="#fff" fill-opacity="0.20"/>',
      `<text x="23" y="28" text-anchor="middle" font-family="Arial, sans-serif" font-size="14" font-weight="800" fill="#fff">${escapeHtml(label)}</text>`,
      '</svg>',
    ].join('');

    return new kakao.maps.MarkerImage(
      `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
      new kakao.maps.Size(46, 46),
      { offset: new kakao.maps.Point(23, 23) },
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

    state.clusterMarkers.forEach(function (marker) {
      marker.setMap(null);
    });
    state.clusterMarkers = [];
  }

  function renderCurrentPositionMarker() {
    if (!state.map || !window.kakao?.maps) return;
    clearCurrentPositionMarker();

    const markerPosition = getCurrentMarkerPosition();
    const position = new kakao.maps.LatLng(
      markerPosition.lat,
      markerPosition.lng,
    );
    const svg = [
      '<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 28 28">',
      '<circle cx="14" cy="14" r="12" fill="#2563eb" fill-opacity="0.22"/>',
      '<circle cx="14" cy="14" r="7" fill="#2563eb" stroke="#fff" stroke-width="3"/>',
      '</svg>',
    ].join('');
    state.currentPositionMarker = new kakao.maps.Marker({
      position: position,
      image: new kakao.maps.MarkerImage(
        `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
        new kakao.maps.Size(28, 28),
        { offset: new kakao.maps.Point(14, 14) },
      ),
      zIndex: 20,
    });
    state.currentPositionMarker.setMap(state.map);

    const accuracy = Number(state.currentAccuracy || 0);
    if (accuracy > 0 && accuracy < 1000) {
      state.currentAccuracyCircle = new kakao.maps.Circle({
        center: position,
        radius: accuracy,
        strokeWeight: 1,
        strokeColor: '#2563eb',
        strokeOpacity: 0.32,
        fillColor: '#2563eb',
        fillOpacity: 0.10,
      });
      state.currentAccuracyCircle.setMap(state.map);
    }
  }

  function getCurrentMarkerPosition() {
    return state.currentPosition ||
      config.DEFAULT_CENTER ||
      DEFAULT_FALLBACK_POSITION;
  }

  function clearCurrentPositionMarker() {
    if (state.currentPositionMarker) {
      state.currentPositionMarker.setMap(null);
      state.currentPositionMarker = null;
    }
    if (state.currentAccuracyCircle) {
      state.currentAccuracyCircle.setMap(null);
      state.currentAccuracyCircle = null;
    }
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
        state.currentAccuracy = position.coords.accuracy || null;
        if (state.map) {
          markProgrammaticMove();
          state.map.setCenter(
            new kakao.maps.LatLng(
              state.currentPosition.lat,
              state.currentPosition.lng,
            ),
          );
        }
        applyFilters();
        loadTouristSpots();
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
    markProgrammaticMove();
    state.map.setLevel(Math.min(state.map.getLevel(), 3));
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
      dismissFloatingUi();
    });
    kakao.maps.event.addListener(state.map, 'dragstart', function () {
      if (shouldIgnoreMapDismiss()) return;
      dismissFloatingUi();
    });
    kakao.maps.event.addListener(state.map, 'zoom_start', function () {
      if (shouldIgnoreMapDismiss()) return;
      dismissFloatingUi();
    });
    kakao.maps.event.addListener(state.map, 'zoom_changed', function () {
      renderMapMarkers();
    });
    state.mapInteractionBound = true;
  }

  function markProgrammaticMove() {
    state.suppressMapDismissUntil = Date.now() + 450;
  }

  function shouldIgnoreMapDismiss() {
    return Date.now() < state.suppressMapDismissUntil;
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

  function getAdaptiveMarkerFocusOffset(offsetY) {
    const width = window.innerWidth || document.documentElement.clientWidth || 0;
    const height = window.innerHeight || document.documentElement.clientHeight || 0;
    if (width <= 480 || height <= 640) return 0;
    return offsetY || 0;
  }

  function positionFloatingPanel(panel, lat, lng, options) {
    if (!panel || !state.map || !window.kakao?.maps) return;

    const offsetY = options?.offsetY ?? 18;
    const minMargin = options?.minMargin ?? 12;
    const shell = $('#map-shell') || document.body;
    const shellRect = shell.getBoundingClientRect();
    const viewportWidth = shell.clientWidth || window.innerWidth;
    const viewportHeight = shell.clientHeight || window.innerHeight;
    const maxWidth = Math.min(
      options?.maxWidth ?? viewportWidth - minMargin * 2,
      viewportWidth - minMargin * 2,
    );
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
          viewportWidth - panelWidth - minMargin,
        );
        const preferredTop = point.y - panelHeight - offsetY;
        const fallbackTop = point.y + offsetY;
        const top = clamp(
          preferredTop >= minMargin ? preferredTop : fallbackTop,
          minMargin,
          viewportHeight - panelHeight - minMargin,
        );
        panel.style.left = `${left}px`;
        panel.style.right = 'auto';
        panel.style.top = `${top}px`;
        panel.style.bottom = 'auto';
        panel.style.transform = 'none';
      } catch (_) {
        panel.style.left = `${Math.max(minMargin, shellRect.width / 2 - 150)}px`;
        panel.style.right = 'auto';
        panel.style.top = `${minMargin}px`;
        panel.style.bottom = 'auto';
        panel.style.transform = 'none';
      }
    });
  }

  function positionFloatingPanelAfterMapSettles(panel, lat, lng, options) {
    if (!panel) return;
    panel.style.visibility = 'hidden';

    window.setTimeout(function () {
      positionFloatingPanel(panel, lat, lng, options);
      panel.style.visibility = 'visible';
    }, options?.delayMs ?? 240);

    window.setTimeout(function () {
      positionFloatingPanel(panel, lat, lng, options);
    }, options?.settleDelayMs ?? 520);
  }

  function showMarkerInfo(item) {
    dismissTransientUi();
    focusMapOnMarker(item.lat, item.lng, 120);

    const panel = document.createElement('div');
    panel.id = 'marker-info';
    panel.className = 'map-panel marker-panel marker-panel-floating';

    const saved = isItemSaved(item);
    const isFlower = item.type === 'flower';

    if (isFlower) {
      panel.classList.add('flower-panel');
      const flowerLabel = item.species || item.name || '꽃';
      const subLabel = item.address ? escapeHtml(item.address) : '';
      const thumbHtml = item.imageUrl
        ? `<div class="flower-thumb"><img src="${escapeHtml(item.imageUrl)}" alt="${escapeHtml(flowerLabel)}" onerror="this.parentElement.classList.add('thumb-error')"/></div>`
        : `<div class="flower-thumb thumb-empty"><span>🌸</span></div>`;
      panel.innerHTML = [
        '<div class="flower-row">',
        thumbHtml,
        '<div class="panel-info">',
        `<strong>${escapeHtml(flowerLabel)}</strong>`,
        subLabel ? `<span>${subLabel}</span>` : '',
        '</div>',
        '</div>',
        '<div class="panel-actions">',
        '<button class="panel-action" data-role="navigate">길찾기</button>',
        `<button class="panel-action save ${saved ? 'saved' : 'ghost'}" data-role="save">${saved ? '저장됨' : '저장'}</button>`,
        '<button class="panel-action ghost" data-role="detail-flower">정보</button>',
        '</div>',
      ].join('');
    } else {
      const meta = [item.period, item.address].filter(Boolean).join(' · ');
      panel.innerHTML = [
        '<div class="panel-info">',
        `<strong>${escapeHtml(item.name || '')}</strong>`,
        meta ? `<span>${escapeHtml(meta)}</span>` : '',
        '</div>',
        '<div class="panel-actions">',
        '<button class="panel-action" data-role="navigate">길찾기</button>',
        `<button class="panel-action save ${saved ? 'saved' : 'ghost'}" data-role="save">${saved ? '저장됨' : '저장'}</button>`,
        item.isFestival || item.isTourist
          ? '<button class="panel-action ghost" data-role="detail">정보</button>'
          : '',
        '</div>',
      ].join('');
    }

    $('#map-shell').appendChild(panel);
    const saveButton = panel.querySelector('[data-role="save"]');
    if (saveButton) {
      saveButton.dataset.saveKey = savedKeyForItem(item);
      saveButton.dataset.itemJson = JSON.stringify({
        id: item.id,
        type: item.type,
        name: item.name,
        address: item.address,
        lat: item.lat,
        lng: item.lng,
        imageUrl: item.imageUrl || '',
        period: item.period || '',
      });
    }
    positionFloatingPanelAfterMapSettles(panel, item.lat, item.lng, {
      offsetY: 22,
      maxWidth: isFlower ? 280 : 260,
    });

    panel.querySelector('[data-role="navigate"]').addEventListener('click', function () {
      panel.remove();
      showRouteModeChooser(item);
    });

    panel.querySelector('[data-role="save"]').addEventListener('click', function () {
      toggleSavedPlace(item);
    });

    const detailButton = panel.querySelector('[data-role="detail"]');
    if (detailButton) {
      detailButton.addEventListener('click', function () {
        if (item.isTourist) {
          showTouristDetail(item.source || item);
        } else {
          showFestivalDetail(item.festival || item);
        }
      });
    }

    const detailFlowerButton = panel.querySelector('[data-role="detail-flower"]');
    if (detailFlowerButton) {
      detailFlowerButton.addEventListener('click', function () {
        panel.remove();
        showFlowerSpotDetail(item);
      });
    }
  }

  function showClusterInfo(group) {
    dismissTransientUi();
    markProgrammaticMove();
    state.map.setLevel(Math.max(1, state.map.getLevel() - 1));
    panToMarkerWithOffset(new kakao.maps.LatLng(group.lat, group.lng), 140);

    const panel = document.createElement('div');
    panel.id = 'cluster-info';
    panel.className = 'map-panel marker-panel marker-panel-floating cluster-panel';
    panel.innerHTML = [
      '<div class="panel-info">',
      `<strong>${escapeHtml(String(group.items.length))}개 장소</strong>`,
      '<span>겹친 마커를 선택하세요.</span>',
      '</div>',
      '<div class="cluster-list">',
      group.items.map(function (item, index) {
        return [
          `<button class="cluster-item" type="button" data-index="${index}">`,
          `<strong>${escapeHtml(item.name || '')}</strong>`,
          `<span>${escapeHtml([item.kindLabel, item.address].filter(Boolean).join(' · '))}</span>`,
          '</button>',
        ].join('');
      }).join(''),
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
    positionFloatingPanelAfterMapSettles(panel, group.lat, group.lng, {
      offsetY: 22,
      maxWidth: 300,
    });

    panel.querySelectorAll('.cluster-item').forEach(function (button) {
      button.addEventListener('click', function () {
        const item = group.items[Number(button.dataset.index)];
        if (item) showMarkerInfo(item);
      });
    });
  }

  function localSavePayload(item) {
    const sourceType = item.type === 'festival'
      ? 'FESTIVAL'
      : item.type === 'tourist'
        ? 'TOURIST'
        : 'FLOWER_SPOT';
    const sourceId = item.id || `${item.lat},${item.lng}`;
    return {
      sourceType: sourceType,
      sourceId: String(sourceId).replace(/^(festival|tourist|flower)-/, ''),
      name: item.name || '',
      address: item.address || '',
      latitude: Number(item.lat || 0),
      longitude: Number(item.lng || 0),
      imageUrl: item.imageUrl || item.source?.imageUrl || '',
      period: item.period || item.source?.period || '',
    };
  }

  function savedKeyForPayload(payload) {
    return `${payload.sourceType}:${payload.sourceId}`;
  }

  function savedKeyForItem(item) {
    return savedKeyForPayload(localSavePayload(item));
  }

  function isItemSaved(item) {
    return state.savedPlaceKeys.has(savedKeyForItem(item));
  }

  function toggleSavedPlace(item) {
    const payload = localSavePayload(item);
    const message = JSON.stringify({
      type: 'toggle_saved_place',
      payload: payload,
    });
    if (window.SaveMarker && typeof window.SaveMarker.postMessage === 'function') {
      window.SaveMarker.postMessage(message);
      return;
    }

    const key = savedKeyForPayload(payload);
    if (state.savedPlaceKeys.has(key)) {
      state.savedPlaceKeys.delete(key);
    } else {
      state.savedPlaceKeys.add(key);
    }
    refreshSaveButtons();
  }

  function refreshSaveButtons() {
    document.querySelectorAll('[data-role="save"][data-save-key]').forEach(function (button) {
      const saved = state.savedPlaceKeys.has(button.dataset.saveKey);
      button.textContent = saved ? '저장됨' : '저장';
      button.classList.toggle('saved', saved);
      button.classList.toggle('ghost', !saved);
    });

    const markerPanel = document.getElementById('marker-info');
    if (!markerPanel) return;
    const saveButton = markerPanel.querySelector('[data-role="save"]');
    if (!saveButton || !saveButton.dataset.itemJson) return;
    try {
      const item = JSON.parse(saveButton.dataset.itemJson);
      const saved = isItemSaved(item);
      saveButton.textContent = saved ? '저장됨' : '저장';
      saveButton.classList.toggle('saved', saved);
      saveButton.classList.toggle('ghost', !saved);
    } catch (_) {}
  }

  function focusMapOnMarker(lat, lng, offsetY) {
    if (!state.map || !window.kakao?.maps) return;
    markProgrammaticMove();
    state.map.setLevel(Math.min(state.map.getLevel(), 3));
    window.setTimeout(function () {
      const position = new kakao.maps.LatLng(lat, lng);
      const adaptiveOffsetY = getAdaptiveMarkerFocusOffset(offsetY);
      if (adaptiveOffsetY === 0) {
        state.map.panTo(position);
      } else {
        panToMarkerWithOffset(position, adaptiveOffsetY);
      }
    }, 80);
  }

  function flowerToMapItem(flower) {
    if (!flower || !flower.location) return null;
    return {
      id: `flower-${flower.flower_id || `${flower.location.lat},${flower.location.lng}`}`,
      type: 'flower',
      kindLabel: flower.species || '꽃',
      name: flower.name || flower.species || '꽃 명소',
      address: flower.address || '',
      lat: flower.location.lat,
      lng: flower.location.lng,
      imageUrl: flower.imageUrl || '',
      species: flower.species || '',
      content: flower.content || '',
      nickname: flower.nickname || '',
      profileImageUrl: flower.profileImageUrl || '',
      createdAt: flower.createdAt || '',
      likeCount: flower.likeCount ?? 0,
      commentCount: flower.commentCount ?? 0,
      postId: flower.flower_id,
      isFestival: false,
      source: flower,
    };
  }

  function findFlowerMapItemById(flowerId) {
    const id = String(flowerId || '').trim();
    if (!id || !state.map || !window.kakao?.maps || !state.flowers.length) return null;
    const flower = state.flowers.find(function (entry) {
      return String(entry.flower_id || '') === id;
    });
    return flowerToMapItem(flower);
  }

  function focusFlowerById(flowerId, openPreview) {
    const id = String(flowerId || '').trim();
    if (!id) return false;
    if (!state.map || !window.kakao?.maps || !state.flowers.length) {
      state.pendingFlowerFocus = { flowerId: id, openPreview: openPreview !== false };
      return false;
    }

    const flower = state.flowers.find(function (entry) {
      return String(entry.flower_id || '') === id;
    });
    if (!flower) {
      state.pendingFlowerFocus = { flowerId: id, openPreview: openPreview !== false };
      return false;
    }

    const item = flowerToMapItem(flower);
    if (!item) return false;
    markProgrammaticMove();
    state.map.setLevel(Math.min(state.map.getLevel(), 3));
    state.map.panTo(new kakao.maps.LatLng(item.lat, item.lng));
    if (openPreview !== false) {
      showMarkerInfo(item);
    }
    return true;
  }

  function focusSavedPlace(place) {
    if (!place || !state.map || !window.kakao?.maps) return false;
    const lat = Number(place.latitude ?? place.mapY ?? place.lat);
    const lng = Number(place.longitude ?? place.mapX ?? place.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return false;
    const sourceType = String(place.sourceType || 'PLACE');
    const item = {
      id: `${sourceType.toLowerCase()}-${place.sourceId || `${lat},${lng}`}`,
      type: sourceType === 'FESTIVAL' ? 'festival' : sourceType === 'TOURIST' ? 'tourist' : 'flower',
      kindLabel: sourceType === 'FESTIVAL' ? '축제' : sourceType === 'TOURIST' ? '관광지' : '꽃 스팟',
      name: place.name || '장소',
      address: place.address || '',
      lat: lat,
      lng: lng,
      imageUrl: place.imageUrl || '',
      period: place.period || '',
      isFestival: sourceType === 'FESTIVAL',
      isTourist: sourceType === 'TOURIST',
      source: place,
    };
    markProgrammaticMove();
    state.map.setLevel(Math.min(state.map.getLevel(), 3));
    state.map.panTo(new kakao.maps.LatLng(lat, lng));
    showMarkerInfo(item);
    return true;
  }

  function openRouteChooserByFlowerId(flowerId) {
    const item = findFlowerMapItemById(flowerId);
    if (!item) {
      state.pendingRouteAction = {
        flowerId: String(flowerId || '').trim(),
        mode: 'none',
      };
      return false;
    }
    showRouteModeChooser(item, { requireCurrentLocation: true });
    return true;
  }

  function startRouteToFlowerById(flowerId, mode) {
    const routeMode = normalizeBridgeRouteMode(mode);
    const item = findFlowerMapItemById(flowerId);
    if (!item) {
      state.pendingRouteAction = {
        flowerId: String(flowerId || '').trim(),
        mode: routeMode,
      };
      return false;
    }
    navigateInApp(
      item.lat,
      item.lng,
      item.name || '꽃 명소',
      routeMode === 'none' ? 'transit' : routeMode,
      { requireCurrentLocation: true },
    );
    return true;
  }

  function applyPendingFlowerFocus() {
    if (!state.pendingFlowerFocus) return;
    const pending = state.pendingFlowerFocus;
    if (focusFlowerById(pending.flowerId, pending.openPreview)) {
      state.pendingFlowerFocus = null;
    }
  }

  function applyPendingRouteAction() {
    if (!state.pendingRouteAction) return;
    const pending = state.pendingRouteAction;
    const applied = pending.mode && pending.mode !== 'none'
      ? startRouteToFlowerById(pending.flowerId, pending.mode)
      : openRouteChooserByFlowerId(pending.flowerId);
    if (applied) {
      state.pendingRouteAction = null;
    }
  }

  function showRouteModeChooser(item, options) {
    removePanel('route-mode-panel');
    focusMapOnMarker(item.lat, item.lng, 156);
    const routeOptions = options || {};

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
      '<button class="panel-action secondary" data-role="car">자동차</button>',
      '<button class="panel-action" data-role="transit">대중교통</button>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
    positionFloatingPanelAfterMapSettles(panel, item.lat, item.lng, {
      offsetY: 22,
      maxWidth: 260,
    });

    panel.querySelector('[data-role="walk"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제', 'walk', routeOptions);
    });

    panel.querySelector('[data-role="car"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제', 'car', routeOptions);
    });

    panel.querySelector('[data-role="transit"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(item.lat, item.lng, item.name || '축제', 'transit', routeOptions);
    });
  }

  function showFlowerSpotDetail(item) {
    dismissTransientUi();

    const panel = document.createElement('section');
    panel.id = 'flower-detail';
    panel.className = 'map-panel detail-panel';

    const imageMarkup = item.imageUrl
      ? `<img src="${escapeAttribute(item.imageUrl)}" alt="${escapeAttribute(item.name || '꽃 사진')}">`
      : '<div class="detail-placeholder">🌸</div>';

    const flowerLabel = item.species || item.name || '꽃 명소';
    const author = item.nickname || '';
    const when = formatPostDate(item.createdAt);
    const authorLine = [author, when].filter(Boolean).join(' · ');

    panel.innerHTML = [
      '<div class="detail-hero">', imageMarkup, '</div>',
      '<div class="detail-content">',
      '<div class="detail-header">',
      `<strong>${escapeHtml(flowerLabel)}</strong>`,
      '<button class="detail-close" type="button" data-role="close" aria-label="닫기">×</button>',
      '</div>',
      authorLine ? `<p class="detail-meta">${escapeHtml(authorLine)}</p>` : '',
      item.address ? `<p class="detail-body">📍 ${escapeHtml(item.address)}</p>` : '',
      item.content
        ? `<p class="detail-body detail-post-content">${escapeHtml(item.content)}</p>`
        : '<p class="detail-body detail-empty">작성자가 남긴 내용이 없습니다.</p>',
      (item.likeCount || item.commentCount)
        ? `<p class="detail-meta">❤ ${item.likeCount || 0}  ·  💬 ${item.commentCount || 0}</p>`
        : '',
      '<div class="detail-actions">',
      '<button class="detail-button secondary" data-role="walk">도보</button>',
      '<button class="detail-button secondary" data-role="car">자동차</button>',
      '<button class="detail-button primary" data-role="transit">대중교통</button>',
      '</div>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);

    panel.querySelector('[data-role="close"]').addEventListener('click', function () {
      panel.remove();
    });

    ['walk', 'car', 'transit'].forEach(function (mode) {
      const btn = panel.querySelector(`[data-role="${mode}"]`);
      if (!btn) return;
      btn.addEventListener('click', function () {
        panel.remove();
        navigateInApp(item.lat, item.lng, item.name || '꽃 명소', mode);
      });
    });
  }

  function formatPostDate(value) {
    if (!value) return '';
    try {
      const d = new Date(value);
      if (Number.isNaN(d.getTime())) return '';
      const yyyy = d.getFullYear();
      const mm = String(d.getMonth() + 1).padStart(2, '0');
      const dd = String(d.getDate()).padStart(2, '0');
      return `${yyyy}.${mm}.${dd}`;
    } catch (_) {
      return '';
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
    const festivalPeriod = getFestivalPeriodText(enrichedFestival, festival);

    panel.innerHTML = [
      '<div class="detail-hero">', imageMarkup, '</div>',
      '<div class="detail-content">',
      '<div class="detail-header">',
      `<strong>${escapeHtml(enrichedFestival.title || '축제')}</strong>`,
      '<button class="detail-close" type="button" data-role="close" aria-label="닫기">×</button>',
      '</div>',
      festivalPeriod ? `<p class="detail-meta">축제 일정: ${escapeHtml(festivalPeriod)}</p>` : '',
      enrichedFestival.eventPlace ? `<p class="detail-body">장소: ${escapeHtml(enrichedFestival.eventPlace)}</p>` : '',
      enrichedFestival.address ? `<p class="detail-body">주소: ${escapeHtml(enrichedFestival.address)}</p>` : '',
      enrichedFestival.useTimeFestival ? `<p class="detail-body">이용안내: ${escapeHtml(enrichedFestival.useTimeFestival)}</p>` : '',
      enrichedFestival.playTime ? `<p class="detail-body">운영시간: ${escapeHtml(enrichedFestival.playTime)}</p>` : '',
      enrichedFestival.homepage ? `<p class="detail-body">홈페이지: ${escapeHtml(stripHtml(enrichedFestival.homepage))}</p>` : '',
      enrichedFestival.tel ? `<p class="detail-body">문의: ${escapeHtml(enrichedFestival.tel)}</p>` : '',
      enrichedFestival.overview ? `<p class="detail-body">${escapeHtml(stripHtml(enrichedFestival.overview))}</p>` : '',
      !festivalPeriod &&
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
      '<button class="detail-button secondary" data-role="car">자동차</button>',
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

    panel.querySelector('[data-role="car"]').addEventListener('click', function () {
      panel.remove();
      navigateInApp(
        enrichedFestival.mapY,
        enrichedFestival.mapX,
        enrichedFestival.title || '축제',
        'car',
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
        period: festival.period ||
          intro.period ||
          formatPeriod(
            festival.eventStartDate || festival.eventstartdate || intro.eventStartDate || '',
            festival.eventEndDate || festival.eventenddate || intro.eventEndDate || '',
          ),
        eventStartDate: festival.eventStartDate || festival.eventstartdate || intro.eventStartDate || '',
        eventEndDate: festival.eventEndDate || festival.eventenddate || intro.eventEndDate || '',
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

  function getFestivalPeriodText(enrichedFestival, originalFestival) {
    const candidates = [
      enrichedFestival?.period,
      formatPeriod(
        enrichedFestival?.eventStartDate || enrichedFestival?.eventstartdate || '',
        enrichedFestival?.eventEndDate || enrichedFestival?.eventenddate || '',
      ),
      originalFestival?.period,
      formatPeriod(
        originalFestival?.eventStartDate || originalFestival?.eventstartdate || '',
        originalFestival?.eventEndDate || originalFestival?.eventenddate || '',
      ),
    ];
    return candidates.find(function (value) {
      return String(value || '').trim();
    }) || '';
  }

  function showTouristDetail(spot) {
    dismissTransientUi();

    const panel = document.createElement('section');
    panel.id = 'festival-detail';
    panel.className = 'map-panel detail-panel';
    const imageUrl = normalizeImageUrl(spot.imageUrl || spot.firstimage2 || spot.firstimage || '');
    const title = spot.title || '관광지';
    const address = spot.address || [spot.addr1 || '', spot.addr2 || ''].join(' ').trim();
    const mapY = Number(spot.mapY ?? spot.mapy ?? spot.lat);
    const mapX = Number(spot.mapX ?? spot.mapx ?? spot.lng);
    const imageMarkup = imageUrl
      ? `<img src="${escapeAttribute(imageUrl)}" alt="${escapeAttribute(title)}">`
      : '<div class="detail-placeholder">관광</div>';

    panel.innerHTML = [
      '<div class="detail-hero">', imageMarkup, '</div>',
      '<div class="detail-content">',
      '<div class="detail-header">',
      `<strong>${escapeHtml(title)}</strong>`,
      '<button class="detail-close" type="button" data-role="close" aria-label="닫기">×</button>',
      '</div>',
      '<p class="detail-meta">꽃 관련 관광지</p>',
      address ? `<p class="detail-body">주소: ${escapeHtml(address)}</p>` : '',
      '<div class="detail-actions">',
      '<button class="detail-button secondary" data-role="walk">도보</button>',
      '<button class="detail-button secondary" data-role="car">자동차</button>',
      '<button class="detail-button primary" data-role="transit">대중교통</button>',
      '</div>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);

    panel.querySelector('[data-role="close"]').addEventListener('click', function () {
      panel.remove();
    });

    ['walk', 'car', 'transit'].forEach(function (mode) {
      panel.querySelector(`[data-role="${mode}"]`).addEventListener('click', function () {
        panel.remove();
        navigateInApp(mapY, mapX, title, mode);
      });
    });
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
      period: formatPeriod(item.eventstartdate || '', item.eventenddate || ''),
      eventStartDate: item.eventstartdate || '',
      eventEndDate: item.eventenddate || '',
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

  async function navigateInApp(destLat, destLng, destName, mode, options) {
    const routeMode = mode || 'walk';
    const hasCurrentPosition = await ensureCurrentPosition({
      requireCurrentLocation: options?.requireCurrentLocation === true,
    });
    if (!hasCurrentPosition) {
      showRouteError('현재 위치 권한이 필요합니다.');
      return;
    }

    showRouteLoading(destName, routeMode);

    try {
      const route = await fetchRoute(
        state.currentPosition.lat,
        state.currentPosition.lng,
        destLat,
        destLng,
        routeMode,
      );
      drawTransitRouteOnMap(route, {
        lat: state.currentPosition.lat,
        lng: state.currentPosition.lng,
      }, {
        lat: destLat,
        lng: destLng,
      });
      showTransitRoutePanel(route, destName);
    } catch (error) {
      console.warn('Route lookup failed.', error);
      showRouteError(
        routeMode === 'transit'
          ? '대중교통 경로를 찾지 못했습니다.'
          : routeMode === 'car'
            ? '자동차 경로를 찾지 못했습니다.'
            : '도보 경로를 찾지 못했습니다.',
      );
    }
  }

  async function ensureCurrentPosition(options) {
    if (state.currentPosition) return true;
    const requireCurrentLocation = options?.requireCurrentLocation === true;

    try {
      await new Promise(function (resolve) {
        if (!navigator.geolocation) {
          if (!requireCurrentLocation) {
            state.currentPosition = DEFAULT_FALLBACK_POSITION;
          }
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
              state.currentAccuracy = position.coords.accuracy || null;
            } else {
              state.currentPosition = requireCurrentLocation ? null : DEFAULT_FALLBACK_POSITION;
              state.currentAccuracy = null;
            }
            resolve();
          },
          function () {
            state.currentPosition = requireCurrentLocation ? null : DEFAULT_FALLBACK_POSITION;
            state.currentAccuracy = null;
            resolve();
          },
          { enableHighAccuracy: true, timeout: 8000 },
        );
      });
    } catch (_) {
      state.currentPosition = requireCurrentLocation ? null : DEFAULT_FALLBACK_POSITION;
      state.currentAccuracy = null;
    }
    return !!state.currentPosition;
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

  async function fetchRoute(startLat, startLng, endLat, endLng, mode) {
    if (!config.API_BASE_URL) {
      throw new Error('Route API base URL is not configured.');
    }

    const response = await fetch(`${config.API_BASE_URL}/map/routes`, {
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
        mode: mode,
      }),
    });

    const body = await response.json().catch(function () {
      return null;
    });

    if (!response.ok || !body?.success || !body?.data) {
      console.warn('Route API failed.', {
        status: response.status,
        mode: mode,
        body: body,
        apiBaseUrl: config.API_BASE_URL,
      });
      throw new Error(body?.error?.message || `Route API ${response.status}`);
    }

    console.log('Route API succeeded.', {
      mode: mode,
      legCount: Array.isArray(body.data.legs) ? body.data.legs.length : 0,
    });
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

    drawStyledRoutePolyline(path, '#4285F4', 14, 0.96);

    drawRouteMarkers(startPos, endPos);

    const bounds = new kakao.maps.LatLngBounds();
    path.forEach(function (point) {
      bounds.extend(point);
    });
    fitRouteBounds(bounds, getRoutePanelHeight());
  }

  function fitRouteBounds(bounds, panelPadding) {
    if (!bounds || !state.map || !window.kakao?.maps) return;
    state.routeBounds = bounds;
    state.map.setBounds(bounds);

    const padding = Math.max(0, Number(panelPadding || 0));
    if (padding <= 0) return;

    window.setTimeout(function () {
      try {
        const projection = state.map.getProjection();
        const center = state.map.getCenter();
        const centerPoint = projection.containerPointFromCoords(center);
        const offsetY = isRoutePanelTopAnchored() ? -padding / 2 : padding / 2;
        const nextPoint = new kakao.maps.Point(
          centerPoint.x,
          centerPoint.y + offsetY,
        );
        state.map.setCenter(projection.coordsFromContainerPoint(nextPoint));
      } catch (error) {
        console.warn('Failed to offset route bounds.', error);
      }
    }, 80);
  }

  function refitVisibleRoute() {
    if (!state.routeBounds) return;
    window.requestAnimationFrame(function () {
      fitRouteBounds(state.routeBounds, getRoutePanelHeight());
    });
  }

  function getRoutePanelHeight() {
    const panel = $('#route-panel');
    const shell = $('#map-shell') || document.body;
    const shellHeight = shell.clientHeight || window.innerHeight || 0;
    if (!panel) return 64;

    const panelHeight = panel.offsetHeight || 64;
    const padding = panelHeight + 36;
    if (!shellHeight) return padding;
    return Math.min(padding, shellHeight * 0.55);
  }

  function isRoutePanelTopAnchored() {
    return Boolean($('#route-panel')?.classList.contains('route-panel-top'));
  }

  function drawTransitRouteOnMap(route, startPos, endPos) {
    clearRoute();
    if (!state.map || !state.kakaoReady || !window.kakao?.maps) return;

    const bounds = new kakao.maps.LatLngBounds();
    let hasPath = false;

    (route.legs || []).forEach(function (leg) {
      const path = (leg.polyline || [])
        .filter(function (point) {
          return Number.isFinite(Number(point.lat)) &&
            Number.isFinite(Number(point.lng));
        })
        .map(function (point) {
          const latLng = new kakao.maps.LatLng(Number(point.lat), Number(point.lng));
          bounds.extend(latLng);
          return latLng;
        });

      if (path.length < 2) return;
      hasPath = true;

      const mode = normalizeRouteMode(leg.mode);
      drawStyledRoutePolyline(
        path,
        normalizeRouteColor(leg.routeColor, mode),
        getRouteStrokeWeight(mode),
        0.99,
      );
    });

    if ((!hasPath || state.routePolylines.length === 0) && startPos && endPos) {
      drawFallbackRouteLine(startPos, endPos, route.mode || 'transit');
      bounds.extend(new kakao.maps.LatLng(startPos.lat, startPos.lng));
      bounds.extend(new kakao.maps.LatLng(endPos.lat, endPos.lng));
      hasPath = true;
    }

    drawRouteMarkers(startPos, endPos);
    if (startPos && Number.isFinite(startPos.lat) && Number.isFinite(startPos.lng)) {
      bounds.extend(new kakao.maps.LatLng(startPos.lat, startPos.lng));
    }
    if (endPos && Number.isFinite(endPos.lat) && Number.isFinite(endPos.lng)) {
      bounds.extend(new kakao.maps.LatLng(endPos.lat, endPos.lng));
    }

    if (hasPath || (startPos && endPos)) {
      fitRouteBounds(bounds, getRoutePanelHeight());
    } else if (endPos) {
      state.map.panTo(new kakao.maps.LatLng(endPos.lat, endPos.lng));
    }
  }

  function drawFallbackRouteLine(startPos, endPos, mode) {
    if (!Number.isFinite(startPos.lat) ||
        !Number.isFinite(startPos.lng) ||
        !Number.isFinite(endPos.lat) ||
        !Number.isFinite(endPos.lng)) {
      return;
    }

    drawStyledRoutePolyline(
      [
        new kakao.maps.LatLng(startPos.lat, startPos.lng),
        new kakao.maps.LatLng(endPos.lat, endPos.lng),
      ],
      normalizeRouteColor('', String(mode || '').toUpperCase()),
      getRouteStrokeWeight(mode),
      0.96,
    );
  }

  function drawStyledRoutePolyline(path, color, weight, opacity) {
    const outline = new kakao.maps.Polyline({
      path: path,
      strokeWeight: weight + 10,
      strokeColor: '#FFFFFF',
      strokeOpacity: 0.96,
      strokeStyle: 'solid',
      zIndex: 29,
    });
    outline.setMap(state.map);
    state.routePolylines.push(outline);

    const polyline = new kakao.maps.Polyline({
      path: path,
      strokeWeight: weight,
      strokeColor: color,
      strokeOpacity: opacity,
      strokeStyle: 'solid',
      zIndex: 30,
    });
    polyline.setMap(state.map);
    state.routePolylines.push(polyline);
  }

  function drawRouteMarkers(startPos, endPos) {
    try {
      if (startPos && Number.isFinite(startPos.lat) && Number.isFinite(startPos.lng)) {
        const startSvg =
          '<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30">'
          + '<circle cx="15" cy="15" r="11" fill="#2563EB" stroke="white" stroke-width="4"/></svg>';
        state.routeStartMarker = new kakao.maps.Marker({
          position: new kakao.maps.LatLng(startPos.lat, startPos.lng),
          image: new kakao.maps.MarkerImage(
            `data:image/svg+xml;charset=utf-8,${encodeURIComponent(startSvg)}`,
            new kakao.maps.Size(30, 30),
            { offset: new kakao.maps.Point(15, 15) },
          ),
        });
        state.routeStartMarker.setMap(state.map);
      }

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
    const normalizedMode = normalizeRouteMode(mode);
    if (normalizedMode === 'WALK') return '#000000';
    if (normalizedMode === 'CAR') return '#6B7280';
    const text = String(color || '').trim();
    if (/^#[0-9a-f]{6}$/i.test(text)) return text;
    if (/^[0-9a-f]{6}$/i.test(text)) return `#${text}`;
    if (normalizedMode === 'SUBWAY') return '#2F80ED';
    if (normalizedMode === 'BUS') return '#27AE60';
    return '#8B5CF6';
  }

  function getRouteStrokeWeight(mode) {
    return normalizeRouteMode(mode) === 'WALK' ? 13 : 14;
  }

  function normalizeRouteMode(mode) {
    return String(mode || '').toUpperCase();
  }

  function clearRoute() {
    state.routePolylines.forEach(function (polyline) {
      polyline.setMap(null);
    });
    state.routePolylines = [];
    state.routeBounds = null;
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
    panel.className = 'map-panel route-panel route-panel-top';

    // 헤더만 먼저 렌더
    panel.innerHTML = [
      '<div class="panel-row">',
      '<span class="panel-icon">도보</span>',
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      `<span>거리 ${escapeHtml(dist)} · 도보 ${escapeHtml(time)}</span>`,
      '</div>',
      steps && steps.length
        ? '<button class="panel-close" data-role="toggle">경로</button>'
        : '',
      '<button class="panel-close-x" data-role="close" aria-label="닫기">×</button>',
      '</div>',
    ].join('');

    // 구간 HTML은 변수로 보관, 토글 시 동적 추가/제거
    let stepsHtml = '';
    if (steps && steps.length) {
      stepsHtml = steps.map(function (step) {
        const stepDistance = step.distance >= 1000
          ? `${(step.distance / 1000).toFixed(1)}km`
          : `${Math.round(step.distance)}m`;
        return [
          '<div class="route-step">',
          `<span class="step-num">${escapeHtml(getStepIcon(step.instruction))}</span>`,
          `<span class="step-text">${escapeHtml(step.instruction)}</span>`,
          `<span class="step-dist">${escapeHtml(stepDistance)}</span>`,
          '</div>',
        ].join('');
      }).join('');
    }

    $('#map-shell').appendChild(panel);
    refitVisibleRoute();

    const toggleButton = panel.querySelector('[data-role="toggle"]');
    if (toggleButton) {
      toggleButton.addEventListener('click', function () {
        const existing = panel.querySelector('#route-steps');
        if (existing) {
          existing.remove();
          toggleButton.textContent = '경로';
        } else {
          const stepsDiv = document.createElement('div');
          stepsDiv.id = 'route-steps';
          stepsDiv.className = 'route-steps';
          stepsDiv.innerHTML = stepsHtml;
          panel.appendChild(stepsDiv);
          toggleButton.textContent = '접기';
        }
        refitVisibleRoute();
      });
    }

    attachRoutePanelCloseHandler(panel);
  }

  function showTransitRoutePanel(route, destName) {
    removePanel('route-panel');

    const summary = route.summary || {};
    const routeMode = String(route.mode || 'transit').toLowerCase();
    const routeLabel = routeMode === 'car'
      ? '자동차'
      : routeMode === 'walk'
        ? '도보'
        : '대중교통';
    const totalTime = formatDuration(summary.totalTimeSec || 0);
    const totalDistance = formatDistance(summary.totalDistanceM || 0);
    const walkTime = formatDuration(summary.totalWalkTimeSec || 0);
    const fare = formatFare(summary.totalFare || 0);
    const transfers = Number(summary.transferCount || 0);

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel route-panel-top transit-route-panel';

    // 헤더만 먼저 렌더 — 구간 토글 시 steps div를 동적 추가/제거
    panel.innerHTML = [
      '<div class="panel-row">',
      `<span class="panel-icon">${escapeHtml(routeMode === 'car' ? '차' : routeMode === 'walk' ? '도보' : '교통')}</span>`,
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      routeMode === 'transit'
        ? `<span>${escapeHtml(routeLabel)} ${escapeHtml(totalTime)} · 거리 ${escapeHtml(totalDistance)} · 도보 ${escapeHtml(walkTime)} · 환승 ${escapeHtml(String(transfers))}회 · ${escapeHtml(fare)}</span>`
        : `<span>${escapeHtml(routeLabel)} ${escapeHtml(totalTime)} · 거리 ${escapeHtml(totalDistance)}</span>`,
      '</div>',
      '<button class="panel-close" data-role="toggle">구간</button>',
      '<button class="panel-close-x" data-role="close" aria-label="닫기">×</button>',
      '</div>',
    ].join('');

    // 구간 HTML은 변수로 보관, DOM에는 토글 시 추가
    const stepsHtml = (route.legs || []).map(buildTransitLegHtml).join('');

    $('#map-shell').appendChild(panel);
    refitVisibleRoute();

    const toggleButton = panel.querySelector('[data-role="toggle"]');
    if (toggleButton) {
      toggleButton.addEventListener('click', function () {
        const existing = panel.querySelector('#route-steps');
        if (existing) {
          existing.remove();
          toggleButton.textContent = '구간';
        } else {
          const stepsDiv = document.createElement('div');
          stepsDiv.id = 'route-steps';
          stepsDiv.className = 'route-steps transit-steps';
          stepsDiv.innerHTML = stepsHtml;
          panel.appendChild(stepsDiv);
          toggleButton.textContent = '접기';
        }
        refitVisibleRoute();
      });
    }

    attachRoutePanelCloseHandler(panel);
  }

  function buildTransitLegHtml(leg) {
    const mode = normalizeRouteMode(leg.mode);
    const modeLabel = getTransitModeLabel(mode);
    const duration = formatDuration(leg.durationSec || 0);
    const distance = formatDistance(leg.distanceM || 0);
    const badgeStyle = mode === 'WALK'
      ? ''
      : ` style="background:${escapeAttribute(normalizeRouteColor(leg.routeColor, mode))};color:#fff;"`;
    const routeLabel = leg.route
      ? `<span class="route-badge"${badgeStyle}>${escapeHtml(leg.route)}</span>`
      : `<span class="route-badge subtle">${escapeHtml(modeLabel)}</span>`;
    const title = mode === 'WALK'
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
      `<div class="transit-leg-header transit-${escapeAttribute(mode.toLowerCase())}">`,
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
      case 'CAR':
        return '자동차';
      case 'WALK':
        return '도보';
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
    const label = mode === 'transit' ? '대중교통' : mode === 'car' ? '자동차' : '도보';

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel route-panel-top';
    panel.innerHTML = [
      '<div class="panel-row">',
      `<span class="panel-icon">${escapeHtml(mode === 'transit' ? '교통' : mode === 'car' ? '차' : '도보')}</span>`,
      '<div class="panel-info">',
      `<strong>${escapeHtml(destName || '')}</strong>`,
      `<span>${escapeHtml(label)} 경로를 찾는 중입니다.</span>`,
      '</div>',
      '<button class="panel-close-x" data-role="close" aria-label="닫기">×</button>',
      '</div>',
    ].join('');
    $('#map-shell').appendChild(panel);
    attachRoutePanelCloseHandler(panel);
  }

  function showRouteError(message) {
    removePanel('route-panel');

    const panel = document.createElement('div');
    panel.id = 'route-panel';
    panel.className = 'map-panel route-panel route-panel-top route-error';
    panel.innerHTML = [
      '<div class="panel-row">',
      '<span class="panel-icon">...</span>',
      `<div class="panel-info"><span>${escapeHtml(message)}</span></div>`,
      '<button class="panel-close-x" data-role="close" aria-label="닫기">×</button>',
      '</div>',
    ].join('');

    $('#map-shell').appendChild(panel);
    attachRoutePanelCloseHandler(panel);
  }

  function attachRoutePanelCloseHandler(panel) {
    const closeButton = panel.querySelector('[data-role="close"]');
    if (!closeButton) return;
    closeButton.addEventListener('click', function () {
      clearRoute();
      removePanel('route-panel');
    });
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

  function dismissFloatingUi() {
    removePanel('marker-info');
    removePanel('cluster-info');
    removePanel('route-mode-panel');
    removePanel('festival-detail');
  }

  function dismissTransientUi() {
    dismissFloatingUi();
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

  function average(values) {
    if (!values.length) return 0;
    return values.reduce(function (sum, value) {
      return sum + Number(value || 0);
    }, 0) / values.length;
  }

  function clamp(value, min, max) {
    if (max < min) return min;
    return Math.min(Math.max(value, min), max);
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


