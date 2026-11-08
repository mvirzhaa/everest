# Isi menu Doa Everest.
# - Ayat Al-Qur'an: teks Arab & terjemah diambil otomatis dari dataset (lihat build_doa.py), BUKAN diketik di sini.
# - Doa dari hadits: teks Arab ditulis di sini; potongan yang berasal dari Al-Qur'an diperiksa otomatis terhadap dataset.
# Mohon ditinjau ustadz/LDK/LPPI kampus sebelum rilis.

# Latin untuk ayat yang sangat sering dibaca (ditulis manual, gaya transliterasi populer Indonesia).
LATIN = {
    (1, 1): "Bismillāhir-rahmānir-rahīm.",
    (1, 2): "Al-hamdu lillāhi rabbil-'ālamīn.",
    (1, 3): "Ar-rahmānir-rahīm.",
    (1, 4): "Māliki yaumid-dīn.",
    (1, 5): "Iyyāka na'budu wa iyyāka nasta'īn.",
    (1, 6): "Ihdinash-shirāthal-mustaqīm.",
    (1, 7): "Shirāthal-ladzīna an'amta 'alaihim, ghairil-maghdhūbi 'alaihim wa ladh-dhāllīn.",
    (2, 255): "Allāhu lā ilāha illā huwal-hayyul-qayyūm, lā ta'khudzuhū sinatuw wa lā naum, lahū mā fis-samāwāti wa mā fil-ardh, "
              "man dzal-ladzī yasyfa'u 'indahū illā bi'idznih, ya'lamu mā baina aidīhim wa mā khalfahum, "
              "wa lā yuhīthūna bisyai'im min 'ilmihī illā bimā syā', wasi'a kursiyyuhus-samāwāti wal-ardh, "
              "wa lā ya'ūduhū hifzhuhumā, wa huwal-'aliyyul-'azhīm.",
    (2, 285): "Āmanar-rasūlu bimā unzila ilaihi mir rabbihī wal-mu'minūn, kullun āmana billāhi wa malā'ikatihī wa kutubihī wa rusulih, "
              "lā nufarriqu baina ahadim mir rusulih, wa qālū sami'nā wa atha'nā ghufrānaka rabbanā wa ilaikal-mashīr.",
    (2, 286): "Lā yukallifullāhu nafsan illā wus'ahā, lahā mā kasabat wa 'alaihā maktasabat, rabbanā lā tu'ākhidznā in nasīnā au akhtha'nā, "
              "rabbanā wa lā tahmil 'alainā ishran kamā hamaltahū 'alal-ladzīna min qablinā, rabbanā wa lā tuhammilnā mā lā thāqata lanā bih, "
              "wa'fu 'annā, waghfir lanā, warhamnā, anta maulānā fanshurnā 'alal-qaumil-kāfirīn.",
    (112, 1): "Qul huwallāhu ahad.",
    (112, 2): "Allāhush-shamad.",
    (112, 3): "Lam yalid wa lam yūlad.",
    (112, 4): "Wa lam yakul lahū kufuwan ahad.",
    (113, 1): "Qul a'ūdzu birabbil-falaq.",
    (113, 2): "Min syarri mā khalaq.",
    (113, 3): "Wa min syarri ghāsiqin idzā waqab.",
    (113, 4): "Wa min syarrin-naffātsāti fil-'uqad.",
    (113, 5): "Wa min syarri hāsidin idzā hasad.",
    (114, 1): "Qul a'ūdzu birabbin-nās.",
    (114, 2): "Malikin-nās.",
    (114, 3): "Ilāhin-nās.",
    (114, 4): "Min syarril-waswāsil-khannās.",
    (114, 5): "Alladzī yuwaswisu fī shudūrin-nās.",
    (114, 6): "Minal-jinnati wan-nās.",
}

TRAVEL = [
    dict(
        id="keluar-rumah",
        title="Doa keluar rumah",
        when="Saat melangkah keluar rumah",
        arabic="بِسْمِ اللهِ، تَوَكَّلْتُ عَلَى اللهِ، لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللهِ",
        latin="Bismillāhi, tawakkaltu 'alallāh, lā haula wa lā quwwata illā billāh.",
        meaning="Dengan nama Allah, aku bertawakal kepada Allah. Tiada daya dan kekuatan kecuali dengan (pertolongan) Allah.",
        source="HR. Abu Dawud no. 5095 dan At-Tirmidzi no. 3426",
    ),
    dict(
        id="keluar-rumah-2",
        title="Doa agar tidak tersesat dan tidak dizalimi",
        when="Saat keluar rumah",
        arabic="اللَّهُمَّ إِنِّي أَعُوذُ بِكَ أَنْ أَضِلَّ أَوْ أُضَلَّ، أَوْ أَزِلَّ أَوْ أُزَلَّ، أَوْ أَظْلِمَ أَوْ أُظْلَمَ، أَوْ أَجْهَلَ أَوْ يُجْهَلَ عَلَيَّ",
        latin="Allāhumma innī a'ūdzu bika an adhilla au udhalla, au azilla au uzalla, au azhlima au uzhlama, au ajhala au yujhala 'alayya.",
        meaning="Ya Allah, aku berlindung kepada-Mu dari tersesat atau disesatkan, tergelincir atau digelincirkan, menzalimi atau dizalimi, "
                "dan dari berbuat bodoh atau diperlakukan dengan bodoh.",
        source="HR. Abu Dawud no. 5094, At-Tirmidzi no. 3427, dan Ibnu Majah no. 3884",
    ),
    dict(
        id="naik-kendaraan",
        title="Doa naik kendaraan",
        when="Saat menaiki motor, mobil, bus, kereta, atau pesawat",
        arabic="بِسْمِ اللهِ، الْحَمْدُ لِلَّهِ، سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ، وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ. "
               "الْحَمْدُ لِلَّهِ (٣×)، اللهُ أَكْبَرُ (٣×)، سُبْحَانَكَ إِنِّي ظَلَمْتُ نَفْسِي فَاغْفِرْ لِي، فَإِنَّهُ لَا يَغْفِرُ الذُّنُوبَ إِلَّا أَنْتَ",
        latin="Bismillāh. Al-hamdu lillāh. Subhānalladzī sakhkhara lanā hādzā wa mā kunnā lahū muqrinīn, wa innā ilā rabbinā lamunqalibūn. "
              "Al-hamdu lillāh (3×), Allāhu akbar (3×). Subhānaka innī zhalamtu nafsī faghfir lī, fa innahū lā yaghfirudz-dzunūba illā ant.",
        meaning="Dengan nama Allah. Segala puji bagi Allah. Mahasuci Allah yang telah menundukkan (kendaraan) ini untuk kami, padahal sebelumnya "
                "kami tidak mampu menguasainya, dan sesungguhnya kami akan kembali kepada Tuhan kami. Segala puji bagi Allah (3×), Allah Mahabesar (3×). "
                "Mahasuci Engkau, sungguh aku telah menzalimi diriku, maka ampunilah aku, karena tidak ada yang mengampuni dosa selain Engkau.",
        source="HR. Abu Dawud no. 2602 dan At-Tirmidzi no. 3446; ayatnya QS. Az-Zukhruf: 13–14",
        quran_fragments=[(43, 13, "سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ"), (43, 14, "وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ")],
    ),
    dict(
        id="safar",
        title="Doa safar (bepergian jauh)",
        when="Setelah duduk di kendaraan saat memulai perjalanan jauh",
        arabic="اللهُ أَكْبَرُ، اللهُ أَكْبَرُ، اللهُ أَكْبَرُ، سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ، وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ، "
               "اللَّهُمَّ إِنَّا نَسْأَلُكَ فِي سَفَرِنَا هَذَا الْبِرَّ وَالتَّقْوَى، وَمِنَ الْعَمَلِ مَا تَرْضَى، اللَّهُمَّ هَوِّنْ عَلَيْنَا سَفَرَنَا هَذَا وَاطْوِ عَنَّا بُعْدَهُ، "
               "اللَّهُمَّ أَنْتَ الصَّاحِبُ فِي السَّفَرِ، وَالْخَلِيفَةُ فِي الْأَهْلِ، اللَّهُمَّ إِنِّي أَعُوذُ بِكَ مِنْ وَعْثَاءِ السَّفَرِ، وَكَآبَةِ الْمَنْظَرِ، وَسُوءِ الْمُنْقَلَبِ فِي الْمَالِ وَالْأَهْلِ",
        latin="Allāhu akbar, Allāhu akbar, Allāhu akbar. Subhānalladzī sakhkhara lanā hādzā wa mā kunnā lahū muqrinīn, wa innā ilā rabbinā lamunqalibūn. "
              "Allāhumma innā nas'aluka fī safarinā hādzal-birra wat-taqwā, wa minal-'amali mā tardhā. Allāhumma hawwin 'alainā safaranā hādzā wathwi 'annā bu'dah. "
              "Allāhumma antash-shāhibu fis-safar, wal-khalīfatu fil-ahl. Allāhumma innī a'ūdzu bika min wa'tsā'is-safar, wa ka'ābatil-manzhar, wa sū'il-munqalabi fil-māli wal-ahl.",
        meaning="Allah Mahabesar (3×). Mahasuci Allah yang telah menundukkan (kendaraan) ini untuk kami, padahal sebelumnya kami tidak mampu menguasainya, "
                "dan sesungguhnya kami akan kembali kepada Tuhan kami. Ya Allah, kami memohon kepada-Mu dalam perjalanan kami ini kebaikan dan takwa, "
                "serta amal yang Engkau ridai. Ya Allah, mudahkanlah perjalanan kami ini dan dekatkanlah jaraknya bagi kami. Ya Allah, Engkaulah pendamping "
                "dalam perjalanan dan pengganti (penjaga) bagi keluarga yang ditinggalkan. Ya Allah, aku berlindung kepada-Mu dari kesulitan perjalanan, "
                "pemandangan yang menyedihkan, dan kepulangan yang buruk dalam harta dan keluarga.",
        source="HR. Muslim no. 1342",
        note="Saat pulang, bacaan yang sama ditambah: Āyibūna tā'ibūna 'ābidūna lirabbinā hāmidūn.",
        quran_fragments=[(43, 13, "سُبْحَانَ الَّذِي سَخَّرَ لَنَا هَذَا وَمَا كُنَّا لَهُ مُقْرِنِينَ"), (43, 14, "وَإِنَّا إِلَى رَبِّنَا لَمُنْقَلِبُونَ")],
    ),
    dict(
        id="naik-kapal",
        title="Doa naik kapal atau perahu",
        when="Saat menaiki kapal, perahu, atau kendaraan air",
        quran=[(11, 41)],
        quran_part="بِسْمِ اللَّهِ مَجْرَاهَا وَمُرْسَاهَا ۚ إِنَّ رَبِّي لَغَفُورٌ رَّحِيمٌ",
        latin="Bismillāhi majrēhā wa mursāhā, inna rabbī laghafūrur rahīm.",
        meaning="Dengan nama Allah pada waktu berlayar dan berlabuhnya. Sesungguhnya Tuhanku benar-benar Maha Pengampun, Maha Penyayang.",
        source="QS. Hud: 41",
    ),
    dict(
        id="menanjak-menurun",
        title="Saat jalan menanjak dan menurun",
        when="Saat kendaraan naik tanjakan atau menuruni jalan",
        arabic="اللهُ أَكْبَرُ\nسُبْحَانَ اللهِ",
        latin="Saat menanjak: Allāhu akbar. Saat menurun: Subhānallāh.",
        meaning="Allah Mahabesar (ketika naik). Mahasuci Allah (ketika turun).",
        source="HR. Al-Bukhari no. 2993, dari Jabir bin Abdillah",
    ),
    dict(
        id="singgah",
        title="Doa singgah di suatu tempat",
        when="Saat berhenti atau menginap di tempat baru",
        arabic="أَعُوذُ بِكَلِمَاتِ اللهِ التَّامَّاتِ مِنْ شَرِّ مَا خَلَقَ",
        latin="A'ūdzu bikalimātillāhit-tāmmāti min syarri mā khalaq.",
        meaning="Aku berlindung dengan kalimat-kalimat Allah yang sempurna dari kejahatan makhluk yang Dia ciptakan.",
        source="HR. Muslim no. 2708",
        note="Siapa yang membacanya saat singgah, tidak ada sesuatu pun yang membahayakannya sampai ia berangkat dari tempat itu.",
    ),
    dict(
        id="masuk-kota",
        title="Doa memasuki kota atau desa",
        when="Saat melihat atau memasuki kota/desa tujuan",
        arabic="اللَّهُمَّ رَبَّ السَّمَاوَاتِ السَّبْعِ وَمَا أَظْلَلْنَ، وَرَبَّ الْأَرَضِينَ السَّبْعِ وَمَا أَقْلَلْنَ، وَرَبَّ الشَّيَاطِينِ وَمَا أَضْلَلْنَ، وَرَبَّ الرِّيَاحِ وَمَا ذَرَيْنَ، "
               "أَسْأَلُكَ خَيْرَ هَذِهِ الْقَرْيَةِ وَخَيْرَ أَهْلِهَا وَخَيْرَ مَا فِيهَا، وَأَعُوذُ بِكَ مِنْ شَرِّهَا وَشَرِّ أَهْلِهَا وَشَرِّ مَا فِيهَا",
        latin="Allāhumma rabbas-samāwātis-sab'i wa mā azhlalna, wa rabbal-aradhīnas-sab'i wa mā aqlalna, wa rabbasy-syayāthīni wa mā adhlalna, "
              "wa rabbar-riyāhi wa mā dzaraina, as'aluka khaira hādzihil-qaryati wa khaira ahlihā wa khaira mā fīhā, "
              "wa a'ūdzu bika min syarrihā wa syarri ahlihā wa syarri mā fīhā.",
        meaning="Ya Allah, Tuhan tujuh langit dan apa yang dinaunginya, Tuhan tujuh bumi dan apa yang dipikulnya, Tuhan setan-setan dan apa yang disesatkannya, "
                "Tuhan angin dan apa yang diterbangkannya. Aku memohon kepada-Mu kebaikan negeri ini, kebaikan penduduknya, dan kebaikan apa yang ada di dalamnya; "
                "dan aku berlindung kepada-Mu dari keburukannya, keburukan penduduknya, dan keburukan apa yang ada di dalamnya.",
        source="HR. An-Nasa'i dalam As-Sunan Al-Kubra, Ibnu Hibban, dan Al-Hakim",
    ),
    dict(
        id="takut-bahaya",
        title="Doa saat khawatir terhadap orang atau bahaya",
        when="Saat merasa terancam oleh suatu kaum atau keadaan",
        arabic="اللَّهُمَّ إِنَّا نَجْعَلُكَ فِي نُحُورِهِمْ، وَنَعُوذُ بِكَ مِنْ شُرُورِهِمْ",
        latin="Allāhumma innā naj'aluka fī nuhūrihim, wa na'ūdzu bika min syurūrihim.",
        meaning="Ya Allah, kami menjadikan Engkau di hadapan mereka (sebagai penghalang), dan kami berlindung kepada-Mu dari kejahatan mereka.",
        source="HR. Abu Dawud no. 1537",
        note="Dalam keadaan darurat, tekan juga tombol Panic agar satpam dan kontak darurat menerima lokasi Anda.",
    ),
    dict(
        id="tergelincir",
        title="Saat kendaraan tergelincir atau tersandung",
        when="Saat kendaraan oleng, tergelincir, atau tersandung",
        arabic="بِسْمِ اللهِ",
        latin="Bismillāh.",
        meaning="Dengan nama Allah.",
        source="HR. Abu Dawud no. 4982",
        note="Nabi ﷺ mengajarkan mengucapkan \"bismillāh\", bukan mencela setan, saat tunggangan tergelincir.",
    ),
    dict(
        id="pamit-musafir",
        title="Doa berpamitan (diucapkan yang bepergian)",
        when="Saat berpamitan kepada keluarga atau teman",
        arabic="أَسْتَوْدِعُكُمُ اللهَ الَّذِي لَا تَضِيعُ وَدَائِعُهُ",
        latin="Astaudi'ukumullāhal-ladzī lā tadhī'u wadā'i'uh.",
        meaning="Aku titipkan kalian kepada Allah, yang tidak akan menyia-nyiakan titipan-Nya.",
        source="HR. Ibnu Majah no. 2825 dan Ahmad",
    ),
    dict(
        id="melepas-musafir",
        title="Doa melepas orang yang bepergian",
        when="Diucapkan oleh yang ditinggal kepada yang akan bepergian",
        arabic="أَسْتَوْدِعُ اللهَ دِينَكَ وَأَمَانَتَكَ وَخَوَاتِيمَ عَمَلِكَ",
        latin="Astaudi'ullāha dīnaka wa amānataka wa khawātīma 'amalik.",
        meaning="Aku titipkan kepada Allah agamamu, amanahmu, dan akhir amalmu.",
        source="HR. Abu Dawud no. 2600 dan At-Tirmidzi no. 3443",
    ),
    dict(
        id="bekal-takwa",
        title="Doa untuk teman yang akan bepergian",
        when="Saat teman meminta didoakan sebelum berangkat",
        arabic="زَوَّدَكَ اللهُ التَّقْوَى، وَغَفَرَ ذَنْبَكَ، وَيَسَّرَ لَكَ الْخَيْرَ حَيْثُمَا كُنْتَ",
        latin="Zawwadakallāhut-taqwā, wa ghafara dzanbaka, wa yassara lakal-khaira haitsumā kunt.",
        meaning="Semoga Allah membekalimu dengan takwa, mengampuni dosamu, dan memudahkan kebaikan bagimu di mana pun engkau berada.",
        source="HR. At-Tirmidzi no. 3444",
    ),
    dict(
        id="pulang",
        title="Doa pulang dari perjalanan",
        when="Saat kembali dari bepergian",
        arabic="آيِبُونَ، تَائِبُونَ، عَابِدُونَ، لِرَبِّنَا حَامِدُونَ",
        latin="Āyibūna, tā'ibūna, 'ābidūna, lirabbinā hāmidūn.",
        meaning="Kami kembali, bertobat, beribadah, dan kepada Tuhan kami kami memuji.",
        source="HR. Al-Bukhari no. 1797 dan Muslim no. 1342",
    ),
    dict(
        id="perlindungan-harian",
        title="Doa perlindungan pagi dan petang",
        when="Dibaca 3× setiap pagi dan petang",
        arabic="بِسْمِ اللهِ الَّذِي لَا يَضُرُّ مَعَ اسْمِهِ شَيْءٌ فِي الْأَرْضِ وَلَا فِي السَّمَاءِ، وَهُوَ السَّمِيعُ الْعَلِيمُ",
        latin="Bismillāhil-ladzī lā yadhurru ma'asmihī syai'un fil-ardhi wa lā fis-samā', wa huwas-samī'ul-'alīm.",
        meaning="Dengan nama Allah, yang bersama nama-Nya tidak ada sesuatu pun di bumi maupun di langit yang dapat membahayakan, dan Dia Maha Mendengar, Maha Mengetahui.",
        source="HR. Abu Dawud no. 5088 dan At-Tirmidzi no. 3388",
        repeat="3×",
    ),
]

KURSI = [
    dict(
        id="ayat-kursi",
        title="Ayat Kursi",
        when="Setelah salat fardu dan sebelum tidur",
        quran=[(2, 255)],
        source="QS. Al-Baqarah: 255",
        note="Siapa yang membacanya sebelum tidur, ia senantiasa dijaga oleh Allah dan setan tidak akan mendekatinya hingga pagi (HR. Al-Bukhari no. 2311).",
    ),
]

RUQYAH_QURAN = [
    ("al-fatihah", "Al-Fatihah", [(1, a) for a in range(1, 8)]),
    ("baqarah-1-5", "Al-Baqarah: 1–5", [(2, a) for a in range(1, 6)]),
    ("baqarah-102", "Al-Baqarah: 102", [(2, 102)]),
    ("baqarah-163-164", "Al-Baqarah: 163–164", [(2, 163), (2, 164)]),
    ("baqarah-255-257", "Al-Baqarah: 255–257 (Ayat Kursi)", [(2, 255), (2, 256), (2, 257)]),
    ("baqarah-285-286", "Al-Baqarah: 285–286", [(2, 285), (2, 286)]),
    ("ali-imran-18-19", "Ali 'Imran: 18–19", [(3, 18), (3, 19)]),
    ("araf-54-56", "Al-A'raf: 54–56", [(7, 54), (7, 55), (7, 56)]),
    ("araf-117-122", "Al-A'raf: 117–122", [(7, a) for a in range(117, 123)]),
    ("yunus-81-82", "Yunus: 81–82", [(10, 81), (10, 82)]),
    ("taha-69", "Taha: 69", [(20, 69)]),
    ("muminun-115-118", "Al-Mu'minun: 115–118", [(23, a) for a in range(115, 119)]),
    ("saffat-1-10", "As-Saffat: 1–10", [(37, a) for a in range(1, 11)]),
    ("ahqaf-29-32", "Al-Ahqaf: 29–32", [(46, a) for a in range(29, 33)]),
    ("rahman-33-35", "Ar-Rahman: 33–35", [(55, 33), (55, 34), (55, 35)]),
    ("hasyr-21-24", "Al-Hasyr: 21–24", [(59, a) for a in range(21, 25)]),
    ("jin-1-9", "Al-Jin: 1–9", [(72, a) for a in range(1, 10)]),
    ("al-ikhlas", "Al-Ikhlas", [(112, a) for a in range(1, 5)]),
    ("al-falaq", "Al-Falaq", [(113, a) for a in range(1, 6)]),
    ("an-nas", "An-Nas", [(114, a) for a in range(1, 7)]),
]

RUQYAH_HADITH = [
    dict(
        id="ruqyah-rabban-nas",
        title="Doa ruqyah untuk orang sakit",
        when="Sambil mengusap bagian yang sakit",
        arabic="اللَّهُمَّ رَبَّ النَّاسِ، أَذْهِبِ الْبَأْسَ، اشْفِهِ وَأَنْتَ الشَّافِي، لَا شِفَاءَ إِلَّا شِفَاؤُكَ، شِفَاءً لَا يُغَادِرُ سَقَمًا",
        latin="Allāhumma rabban-nās, adzhibil-ba's, isyfihi wa antasy-syāfī, lā syifā'a illā syifā'uk, syifā'an lā yughādiru saqamā.",
        meaning="Ya Allah, Tuhan manusia, hilangkanlah penyakit ini, sembuhkanlah ia, Engkaulah Yang Maha Menyembuhkan. Tidak ada kesembuhan "
                "kecuali kesembuhan dari-Mu, kesembuhan yang tidak meninggalkan penyakit.",
        source="HR. Al-Bukhari no. 5743",
    ),
    dict(
        id="ruqyah-jibril",
        title="Ruqyah yang dibacakan Jibril untuk Nabi ﷺ",
        when="Dibacakan kepada orang yang sakit",
        arabic="بِاسْمِ اللهِ أَرْقِيكَ، مِنْ كُلِّ شَيْءٍ يُؤْذِيكَ، مِنْ شَرِّ كُلِّ نَفْسٍ أَوْ عَيْنِ حَاسِدٍ، اللهُ يَشْفِيكَ، بِاسْمِ اللهِ أَرْقِيكَ",
        latin="Bismillāhi arqīk, min kulli syai'in yu'dzīk, min syarri kulli nafsin au 'aini hāsid, Allāhu yasyfīk, bismillāhi arqīk.",
        meaning="Dengan nama Allah aku meruqyahmu, dari segala sesuatu yang menyakitimu, dari kejahatan setiap jiwa atau mata orang yang dengki. "
                "Semoga Allah menyembuhkanmu. Dengan nama Allah aku meruqyahmu.",
        source="HR. Muslim no. 2186",
    ),
    dict(
        id="ruqyah-sakit-sendiri",
        title="Saat merasakan sakit di tubuh sendiri",
        when="Letakkan tangan di bagian yang sakit",
        arabic="بِسْمِ اللهِ (٣×)، أَعُوذُ بِاللهِ وَقُدْرَتِهِ مِنْ شَرِّ مَا أَجِدُ وَأُحَاذِرُ (٧×)",
        latin="Bismillāh (3×). A'ūdzu billāhi wa qudratihī min syarri mā ajidu wa uhādzir (7×).",
        meaning="Dengan nama Allah (3×). Aku berlindung kepada Allah dan kekuasaan-Nya dari keburukan yang aku rasakan dan aku khawatirkan (7×).",
        source="HR. Muslim no. 2202",
    ),
    dict(
        id="ruqyah-perlindungan-anak",
        title="Perlindungan dari setan dan 'ain",
        when="Dibacakan untuk diri sendiri atau anak",
        arabic="أَعُوذُ بِكَلِمَاتِ اللهِ التَّامَّةِ، مِنْ كُلِّ شَيْطَانٍ وَهَامَّةٍ، وَمِنْ كُلِّ عَيْنٍ لَامَّةٍ",
        latin="A'ūdzu bikalimātillāhit-tāmmah, min kulli syaithānin wa hāmmah, wa min kulli 'ainin lāmmah.",
        meaning="Aku berlindung dengan kalimat-kalimat Allah yang sempurna dari setiap setan dan binatang berbisa, serta dari setiap mata yang jahat.",
        source="HR. Al-Bukhari no. 3371",
        note="Nabi ﷺ membacakannya untuk Hasan dan Husain. Untuk anak, ganti \"a'ūdzu\" menjadi \"u'īdzuka\" (aku memohonkan perlindungan untukmu).",
    ),
]
