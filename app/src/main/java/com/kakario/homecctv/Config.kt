package com.kakario.homecctv

/** 공개돼도 괜찮은 값만 둔다 (google-services.json 수준). 비밀값 금지. */
object Config {
    const val API_KEY = "AIzaSyBzTPF-FB-FQHXwn8ozzEksTSrlHO_Nwdk"
    const val DB_URL = "https://kakario-apps-default-rtdb.asia-southeast1.firebasedatabase.app"
    const val RELEASES_API = "https://api.github.com/repos/kakario777-netizen/home-cctv/releases/latest"
    val STUN = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
}
